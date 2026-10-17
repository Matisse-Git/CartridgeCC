// Install the pinned official validator only in .tools; see ASSET_PIPELINE.md.
const fs = require('fs');
const path = require('path');
const validator = require('../.tools/gltf-validation/node_modules/gltf-validator');
(async () => {
    for (const name of ['gba_cartridge.glb', 'gb_cartridge.glb', 'ps1_disc.glb', 'nes_cartridge.glb', 'snes_cartridge.glb',
        'snes_us_cartridge.glb', 'genesis_cartridge.glb', 'jewel_case.glb', 'gba_box.glb', 'gba_box_jp.glb']) {
        const file = path.join(__dirname, '../app/src/main/assets/models', name);
        const report = await validator.validateBytes(new Uint8Array(fs.readFileSync(file)), {uri: name});
        fs.writeFileSync(path.join(__dirname, '../.tools', name + '.validation.json'), JSON.stringify(report, null, 2));
        console.log(name + ': ' + report.issues.numErrors + ' errors, ' + report.issues.numWarnings + ' warnings');
        if (report.issues.numErrors || report.issues.numWarnings) process.exitCode = 1;
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
