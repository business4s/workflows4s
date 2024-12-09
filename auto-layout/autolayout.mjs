// Import the necessary modules
import fs from 'fs/promises'; // File System module to read and write files
import { layoutProcess } from 'bpmn-auto-layout'; // Import the layoutProcess function

// Function to read a BPMN file, layout it, and write the result to another file
async function layoutBpmnFile(inputPath, outputPath) {
    try {
        // Read the BPMN XML from the input file
        const diagramXML = await fs.readFile(inputPath, { encoding: 'utf8' });

        // Use bpmn-auto-layout to layout the diagram
        const layoutedDiagramXML = await layoutProcess(diagramXML);

        // Write the layouted diagram XML to the output file
        await fs.writeFile(outputPath, layoutedDiagramXML, { encoding: 'utf8' });

        console.log(`Layout process completed. Output saved to: ${outputPath}`);
    } catch (error) {
        console.error('Error during the layout process:', error);
    }
}

// Specify the input and output paths
const inputPath = '../workflows4s-example/src/test/resources/checks-engine.bpmn'; // Path X
const outputPath = `../workflows4s-example/src/test/resources/checks-engine-layouted.bpmn`; // Path Y

// Call the function with the specified paths
layoutBpmnFile(inputPath, outputPath);

const loopExample = '../workflows4s-example/src/test/resources/docs/loop.bpmn'

layoutBpmnFile(loopExample, loopExample)