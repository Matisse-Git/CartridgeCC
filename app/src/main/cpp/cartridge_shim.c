/* Original readiness instrumentation shared by every core that needs no extension of its own.
 * cartridge_core() in cores.cmake compiles the fetched, unchanged core with its callback setters
 * renamed, and links this file in to provide them under their Libretro names.
 *
 * Built a second time with CARTRIDGE_HW_FRAMES for cores that draw with OpenGL ES themselves. The
 * host (LibretroDroid 0.14.0) then copies the core's framebuffer to the screen inside the video
 * callback, in the core's own GL context, with client-side vertex arrays and a framebuffer exactly
 * as large as the base size the core last announced. A core written for a frontend that gives it a
 * context of its own keeps none of those assumptions, so this file keeps them: the host's copy sees
 * a clean context, and the core gets back the one it left.
 */
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef void (*cartridge_video_t)(const void* data, unsigned width, unsigned height, size_t pitch);
typedef size_t (*cartridge_audio_t)(const int16_t* data, size_t frames);

void cartridge_core_set_video_refresh(cartridge_video_t callback);
void cartridge_core_set_audio_sample_batch(cartridge_audio_t callback);

static cartridge_video_t video;
static cartridge_audio_t audio;
static uint64_t video_frames;
static uint64_t audio_frames;

/* What a session did, for diagnostics on the device (SaveBridge.stats). Counts since the callbacks were set. */
enum { RUNS, CORE_RUNS, DRAWN, REPEATED, SILENT, AUDIO_IN, AUDIO_CALLS, AUDIO_ELSEWHERE, AUDIO_LARGEST, AUDIO_OUT, BOUND, SIZE, AUDIO_DROPPED, STATS };
static uint64_t stats[STATS];
static pthread_t runner;                /* The thread the host runs the core on. */

#ifdef CARTRIDGE_HW_FRAMES
#include <GLES3/gl3.h>

/* libretro.h: RETRO_HW_FRAME_BUFFER_VALID, RETRO_ENVIRONMENT_SET_HW_RENDER, _SET_SYSTEM_AV_INFO and
 * _SET_GEOMETRY, and the structures those three carry. */
#define HW_FRAME ((const void*)-1)
#define SET_HW_RENDER 14u
#define SET_SYSTEM_AV_INFO 32u
#define SET_GEOMETRY 37u
struct geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct av_info { struct geometry geometry; struct { double fps, sample_rate; } timing; };
struct hw_render {
    int context_type; void (*context_reset)(void); uintptr_t (*get_current_framebuffer)(void); void* (*get_proc_address)(const char*);
    bool depth, stencil, bottom_left_origin; unsigned version_major, version_minor; bool cache_context;
    void (*context_destroy)(void); bool debug_context;
};

typedef bool (*cartridge_environment_t)(unsigned command, void* data);
void cartridge_core_set_environment(cartridge_environment_t callback);
void cartridge_core_run(void);
void cartridge_core_unload_game(void);
void retro_get_system_av_info(struct av_info* info);    /* The core's own. */

static cartridge_environment_t frontend;
static struct geometry announced;       /* What the core last said; its aspect ratio is the one to keep. */
static unsigned drawn_width, drawn_height;
static uintptr_t (*host_framebuffer)(void);
static bool reported;                   /* The core called the video callback during this frame. */
static void (*context_destroy)(void);   /* The core's own, which the host is not given directly. */
static bool unloaded;                   /* The game was already unloaded on the thread the core ran on. */
static void present(const void* data, unsigned width, unsigned height, size_t pitch);

/* What the host calls when it lets go of the context. See cartridge_unload. */
static void destroyed(void) { if (!unloaded && context_destroy) context_destroy(); }

/* Pacing by the core's own sound.
 *
 * The host runs a core once for every picture the screen shows and plays whatever sound each run hands it, never
 * waiting. That is right for a core that emulates one sixtieth of a second per run. These engines emulate until
 * the game has drawn its next picture: a game that draws thirty a second advances two sixtieths per run, runs
 * at twice its speed, and hands over twice the sound the host can play, which the host then drops in pieces.
 * A frontend holds such a core back by its sound: it runs the core only when it needs more to play.
 *
 * So the core's sound is held here, the host is handed one run's worth each time it asks for a frame, and the
 * core is not run while enough is still waiting: the game then keeps its own time, whatever its frame rate, and
 * the host hears an even stream. A core that is behind is never held back.
 *
 * How much is enough depends on what a run brings. An engine that brings several runs' worth at a time is the
 * case above, and is held back as soon as half a run's worth is waiting. One that brings a run's worth on
 * average is keeping time by itself, and may do so in pieces larger than a run (ARMSX2 hands over up to 2048 frames at a
 * time, against 800 a run): it is left alone until three runs' worth is waiting, which
 * only a real surplus reaches.
 *
 * Nothing that is held is thrown away to catch up. A game that is loading may show no picture for a third of a
 * second, and the engine then hands over that third of a second of sound from one run; the core rests until it
 * has been played, which is the time the game itself stood still for. Cutting it short (as this first did) made
 * loading a little quicker and tore the music at every load. */
enum { HELD = 1 << 15 };                /* Frames the queue can hold, about two thirds of a second; beyond it the oldest go. */
static int16_t held[HELD * 2];
static size_t held_first, held_count;
static pthread_mutex_t held_lock = PTHREAD_MUTEX_INITIALIZER;
static double per_run;                  /* Frames of sound the host expects of one run: the rate over the frame rate. */
static double owed;                     /* The fraction of a frame carried to the next run. */
static double frames_per_second;        /* The frame rate the host took at the start and keeps. */
static double brought;                  /* What a run of the core brings, in runs' worth: a running average. */
static uint64_t counted;                /* The sound received by the time of the last run. */

static void hold(const int16_t* data, size_t frames) {
    pthread_mutex_lock(&held_lock);
    for (size_t n = 0; n < frames; ++n) {
        if (held_count == HELD) { held_first = (held_first + 1) % HELD; --held_count; ++stats[AUDIO_DROPPED]; }
        size_t at = (held_first + held_count++) % HELD;
        held[at * 2] = data[n * 2]; held[at * 2 + 1] = data[n * 2 + 1];
    }
    pthread_mutex_unlock(&held_lock);
}

/* One run's worth to the host, or what there is. */
static void release(void) {
    static int16_t out[4096 * 2];
    if (!audio) return;
    size_t wanted = sizeof out / sizeof *out / 2;
    if (per_run >= 1) {
        owed += per_run;
        size_t whole = (size_t)owed;
        owed -= (double)whole;
        if (whole < wanted) wanted = whole;
    }
    pthread_mutex_lock(&held_lock);
    size_t frames = held_count < wanted ? held_count : wanted;
    for (size_t n = 0; n < frames; ++n) {
        size_t at = (held_first + n) % HELD;
        out[n * 2] = held[at * 2]; out[n * 2 + 1] = held[at * 2 + 1];
    }
    held_first = (held_first + frames) % HELD; held_count -= frames;
    pthread_mutex_unlock(&held_lock);
    if (frames) { stats[AUDIO_OUT] += frames; audio(out, frames); }
}

/* The rate is read again now and then: an engine may change it with the game's own sound hardware. */
static void measure(void) {
    struct av_info info = {0};
    retro_get_system_av_info(&info);
    if (frames_per_second < 1) frames_per_second = info.timing.fps >= 1 ? info.timing.fps : 60;
    per_run = info.timing.sample_rate >= 1 ? info.timing.sample_rate / frames_per_second : 0;
}

/* A core may name a nominal base size and draw larger (an internal-resolution option). The host sizes
 * its framebuffer from the base size, so it is told the size that is actually drawn. */
static bool environment(unsigned command, void* data) {
    if (data && command == SET_GEOMETRY) {
        struct geometry copy = *(const struct geometry*)data;
        announced = copy;
        if (drawn_width) { copy.base_width = drawn_width; copy.base_height = drawn_height; }
        return frontend(command, &copy);
    }
    if (data && command == SET_SYSTEM_AV_INFO) {
        struct av_info copy = *(const struct av_info*)data;
        announced = copy.geometry;
        if (drawn_width) { copy.geometry.base_width = drawn_width; copy.geometry.base_height = drawn_height; }
        return frontend(command, &copy);
    }
    if (data && command == SET_HW_RENDER) {
        struct hw_render* render = data;
        void (*own)(void) = render->context_destroy;
        render->context_destroy = destroyed;    /* The host keeps this one; the core's structure is put back as it was. */
        bool accepted = frontend(command, data);
        render->context_destroy = own;
        context_destroy = accepted ? own : NULL;
        /* The host has now written where its framebuffer is asked for. */
        host_framebuffer = accepted ? render->get_current_framebuffer : NULL;
        return accepted;
    }
    return frontend(command, data);
}

/* Two more things a frontend does around a core's frame, which the host does not.
 *
 * It hands the core its frame with the framebuffer for that frame already bound, and an engine that remembers
 * its own bindings counts on it: one that only shows a picture, drawing nothing new, never binds the
 * framebuffer it believes is still current. The host leaves the screen bound instead.
 *
 * And it keeps the last picture on screen when a core ends a frame without a word, as these engines do while
 * the game has shown nothing new. The host draws only inside the video callback and swaps the screen's
 * buffers regardless, which would show whatever an older buffer held; so silence is reported to it as what
 * it is, a repeated frame. */
__attribute__((visibility("default")))
void retro_run(void) {
    runner = pthread_self();
    ++stats[RUNS];
    if (host_framebuffer) {
        /* Only the screen is replaced. A framebuffer of the engine's own is where the engine believes it is,
         * and an engine that skips a binding it remembers would draw its scene into the host's picture. */
        GLint bound = 0;
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &bound);
        if (!bound) { glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)host_framebuffer()); ++stats[BOUND]; }
    }
    if (stats[RUNS] % 64 == 1) measure();
    pthread_mutex_lock(&held_lock);
    bool ahead = per_run >= 1 && (double)held_count >= per_run * (brought >= 1.3 ? 0.5 : 3);
    uint64_t received = stats[AUDIO_IN];
    pthread_mutex_unlock(&held_lock);
    reported = false;
    if (!ahead) {
        /* Since the run before this one, wherever the engine makes its sound. */
        if (per_run >= 1) brought += ((double)(received - counted) / per_run - brought) / 16;
        counted = received;
        ++stats[CORE_RUNS];
        cartridge_core_run();
    }
    if (!reported && video && host_framebuffer && drawn_width) { ++stats[SILENT]; present(NULL, drawn_width, drawn_height, 0); }
    release();
}

__attribute__((visibility("default")))
void retro_set_environment(cartridge_environment_t callback) {
    frontend = callback;
    cartridge_core_set_environment(environment);
}

/* The host lets go of the context and unloads the game from its main thread. An engine may have set something
 * up for the thread it ran on (Dolphin gives that thread a signal stack and frees "the current thread's" when
 * it stops), so LibretroEngine has both done on that thread first, through this; the host's own two calls then
 * find nothing left to do.
 *
 * The context first, then the game: the host's own order. CARTRIDGE_UNLOAD_FIRST turns it round for an engine
 * that draws on a thread of its own (ARMSX2): told that the context is going, it gives up everything it drew
 * with at once, and its emulation, still running until the game is unloaded, then draws with nothing. Unloaded
 * first, it stops and frees what it made while the context it made it in is still there. */
__attribute__((visibility("default")))
void cartridge_unload(void) {
    if (unloaded) return;
#ifdef CARTRIDGE_UNLOAD_FIRST
    cartridge_core_unload_game();
    if (context_destroy) context_destroy();
#else
    if (context_destroy) context_destroy();
    cartridge_core_unload_game();
#endif
    unloaded = true;
}

__attribute__((visibility("default")))
void retro_unload_game(void) {
    if (!unloaded) cartridge_core_unload_game();
    unloaded = false;
    context_destroy = NULL;
    host_framebuffer = NULL;
}

static void resized(unsigned width, unsigned height) {
    struct geometry now = announced;        /* An aspect ratio of zero leaves the host on the core's default. */
    drawn_width = now.base_width = width;
    drawn_height = now.base_height = height;
    if (now.max_width < width) now.max_width = width;
    if (now.max_height < height) now.max_height = height;
    if (frontend) frontend(SET_GEOMETRY, &now);
}

/* The host's copy to the screen. Nothing of the core's may be bound where the host passes client
 * memory or samples its texture, and nothing may mask the copy. The host in turn binds the screen,
 * its own program and texture, and may rebuild its framebuffer; an engine that remembers what it
 * bound and skips repeating it (Dolphin does) would go on drawing into the wrong place with nothing
 * selected. So every piece of state the host's copy touches is read first and put back after. */
static void present(const void* data, unsigned width, unsigned height, size_t pitch) {
    static const GLenum switches[] = { GL_SCISSOR_TEST, GL_BLEND, GL_CULL_FACE, GL_STENCIL_TEST, GL_DEPTH_TEST };
    enum { SWITCHES = sizeof(switches) / sizeof(*switches), UNITS = 2 };
    GLboolean enabled[SWITCHES], mask[4];
    GLfloat clear[4];
    GLint vertex_array, array_buffer, unit, sampler[UNITS], texture[UNITS], program, draw, read, renderbuffer, viewport[4];

    for (int n = 0; n < SWITCHES; ++n) {
        enabled[n] = glIsEnabled(switches[n]);
        if (enabled[n]) glDisable(switches[n]);
    }
    glGetBooleanv(GL_COLOR_WRITEMASK, mask);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
    glGetIntegerv(GL_VIEWPORT, viewport);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read);
    glGetIntegerv(GL_RENDERBUFFER_BINDING, &renderbuffer);
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &unit);
    for (int n = 0; n < UNITS; ++n) {
        glActiveTexture(GL_TEXTURE0 + n);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture[n]);
        glGetIntegerv(GL_SAMPLER_BINDING, &sampler[n]);
        if (sampler[n]) glBindSampler(n, 0);
    }
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &array_buffer);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vertex_array);
    glBindVertexArray(0);

    video(data, width, height, pitch);

    glBindVertexArray((GLuint)vertex_array);
    glBindBuffer(GL_ARRAY_BUFFER, (GLuint)array_buffer);
    for (int n = 0; n < UNITS; ++n) {
        glActiveTexture(GL_TEXTURE0 + n);
        /* The host may have replaced its framebuffer, and with it a texture or buffer the core had bound. */
        glBindTexture(GL_TEXTURE_2D, glIsTexture((GLuint)texture[n]) ? (GLuint)texture[n] : 0);
        if (sampler[n]) glBindSampler(n, (GLuint)sampler[n]);
    }
    glActiveTexture((GLenum)unit);
    glUseProgram((GLuint)program);
    glBindRenderbuffer(GL_RENDERBUFFER, glIsRenderbuffer((GLuint)renderbuffer) ? (GLuint)renderbuffer : 0);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, glIsFramebuffer((GLuint)draw) ? (GLuint)draw : 0);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, glIsFramebuffer((GLuint)read) ? (GLuint)read : 0);
    glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    glClearColor(clear[0], clear[1], clear[2], clear[3]);
    glColorMask(mask[0], mask[1], mask[2], mask[3]);
    for (int n = 0; n < SWITCHES; ++n) {
        if (enabled[n]) glEnable(switches[n]); else glDisable(switches[n]);
    }
}
#endif

/* A null buffer repeats the previous frame; LibretroDroid may also report a timing step that ran no
 * core frame. A hardware-rendered frame arrives as a non-null marker and is a produced frame. */
static void refresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (data) { ++video_frames; ++stats[DRAWN]; stats[SIZE] = (uint64_t)width << 16 | height; } else ++stats[REPEATED];
    if (!video) return;
#ifdef CARTRIDGE_HW_FRAMES
    reported = true;
    /* The host copies the core's framebuffer to the screen inside this call, for a repeated frame as well. */
    if (host_framebuffer) {
        if (data == HW_FRAME && width && height && (width != drawn_width || height != drawn_height)) resized(width, height);
        present(data, width, height, pitch);
        return;
    }
#endif
    video(data, width, height, pitch);
}

/* Diagnostic: non-silent PCM handed to the frontend, not acoustic output. */
static size_t batch(const int16_t* data, size_t frames) {
    if (data) for (size_t n = 0; n < frames * 2; ++n) {
        if (data[n]) { audio_frames += frames; break; }
    }
    stats[AUDIO_IN] += frames; ++stats[AUDIO_CALLS];
    if (frames > stats[AUDIO_LARGEST]) stats[AUDIO_LARGEST] = frames;
    if (stats[RUNS] && !pthread_equal(pthread_self(), runner)) ++stats[AUDIO_ELSEWHERE];
#ifdef CARTRIDGE_HW_FRAMES
    if (data) hold(data, frames);
    return frames;
#else
    stats[AUDIO_OUT] += frames;
    return audio ? audio(data, frames) : frames;
#endif
}

__attribute__((visibility("default")))
void retro_set_video_refresh(cartridge_video_t callback) {
    video = callback;
    video_frames = 0;
    audio_frames = 0;
    for (int n = 0; n < STATS; ++n) stats[n] = 0;
#ifdef CARTRIDGE_HW_FRAMES
    drawn_width = drawn_height = 0;
    host_framebuffer = NULL;
    reported = false;
    unloaded = false;
    context_destroy = NULL;
    pthread_mutex_lock(&held_lock);
    held_first = held_count = 0;
    pthread_mutex_unlock(&held_lock);
    per_run = owed = frames_per_second = brought = 0;
    counted = 0;
#endif
    cartridge_core_set_video_refresh(refresh);
}

__attribute__((visibility("default")))
void retro_set_audio_sample_batch(cartridge_audio_t callback) {
    audio = callback;
    cartridge_core_set_audio_sample_batch(batch);
}

__attribute__((visibility("default")))
uint64_t cartridge_video_frames(void) { return video_frames; }

__attribute__((visibility("default")))
uint64_t cartridge_audio_frames(void) { return audio_frames; }

__attribute__((visibility("default")))
unsigned cartridge_stats(uint64_t* out, unsigned count) {
    unsigned copied = count < STATS ? count : STATS;
    for (unsigned n = 0; n < copied; ++n) out[n] = stats[n];
    return copied;
}
