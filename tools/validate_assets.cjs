// Install the pinned official validator only in .tools; see ASSET_PIPELINE.md.
const fs = require('fs');
const path = require('path');
const validator = require('../.tools/gltf-validation/node_modules/gltf-validator');
(async () => {
    for (const name of fs.readdirSync(path.join(__dirname, '../app/src/main/assets/models')).filter(name => name.endsWith('.glb'))) {
        const file = path.join(__dirname, '../app/src/main/assets/models', name);
        const report = await validator.validateBytes(new Uint8Array(fs.readFileSync(file)), {uri: name});
        fs.writeFileSync(path.join(__dirname, '../.tools', name + '.validation.json'), JSON.stringify(report, null, 2));
        console.log(name + ': ' + report.issues.numErrors + ' errors, ' + report.issues.numWarnings + ' warnings');
        if (report.issues.numErrors || report.issues.numWarnings) process.exitCode = 1;
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
