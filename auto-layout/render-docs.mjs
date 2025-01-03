import { readdir } from 'fs/promises';
import { join, parse } from 'path';
import { convertAll } from 'bpmn-to-image';

// Function to convert all .bpmn files in the specified directory to SVG images
async function convertBpmnToSvg(directoryPath) {
    try {
        // Read all files in the directory
        const files = await readdir(directoryPath);

        // Filter out files with the .bpmn extension
        const bpmnFiles = files.filter(file => file.endsWith('.bpmn'));

        // Prepare the input for the convertAll function
        const conversions = bpmnFiles.map(file => ({
            input: join(directoryPath, file),
            outputs: [join(directoryPath, `${parse(file).name}.svg`)]
        }));

        // If there are BPMN files to convert
        if (conversions.length > 0) {
            // Convert all found BPMN files to SVG
            await convertAll(conversions);
            console.log('Conversion completed successfully.');
        } else {
            console.log('No .bpmn files found in the directory.');
        }
    } catch (error) {
        console.error('An error occurred:', error);
    }
}

convertBpmnToSvg('./../workflows4s-example/src/test/resources/docs');