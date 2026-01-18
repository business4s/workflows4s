import { readdir } from 'fs/promises';
import { join, parse } from 'path';
import { convertAll } from 'bpmn-to-image';

// Recursively convert all .bpmn files under the specified directory to SVG images
async function collectBpmnConversions(dir) {
    const entries = await readdir(dir, { withFileTypes: true });
    const conversions = [];
    for (const entry of entries) {
        const fullPath = join(dir, entry.name);
        if (entry.isDirectory()) {
            const nested = await collectBpmnConversions(fullPath);
            conversions.push(...nested);
        } else if (entry.isFile() && entry.name.endsWith('.bpmn')) {
            conversions.push({
                input: fullPath,
                outputs: [join(dir, `${parse(entry.name).name}.svg`)],
            });
        }
    }
    return conversions;
}

async function convertBpmnToSvgRecursive(rootDir) {
    try {
        const conversions = await collectBpmnConversions(rootDir);
        if (conversions.length > 0) {
            await convertAll(conversions);
            console.log(`Converted ${conversions.length} BPMN files to SVG.`);
        } else {
            console.log('No .bpmn files found.');
        }
    } catch (error) {
        console.error('An error occurred:', error);
    }
}

convertBpmnToSvgRecursive('./../workflows4s-example/src/test/resources/docs');