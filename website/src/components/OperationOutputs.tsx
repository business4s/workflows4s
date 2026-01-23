import React from 'react';
import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import Mermaid from "@theme/Mermaid";
import CodeBlock from "@theme/CodeBlock";

interface OperationOutputsProps {
    name: string;
    showBpmn?: boolean;
}

const OperationOutputs: React.FC<OperationOutputsProps> = ({name, showBpmn = true}) => {
    const mermaidSource = require(`!!raw-loader!../../../workflows4s-example/src/test/resources/docs/${name}/diagram.mermaid`).default;
    const jsonSource = require(`!!raw-loader!../../../workflows4s-example/src/test/resources/docs/${name}/model.json`).default;
    const debugSource = require(`!!raw-loader!../../../workflows4s-example/src/test/resources/docs/${name}/debug.txt`).default;
    const svgPath = showBpmn ? require(`../../../workflows4s-example/src/test/resources/docs/${name}/diagram.svg`).default : "";

    return (
        <details>
            <summary style={{ cursor: 'pointer', marginBottom: '0.5em' }}>
                Rendering Outputs
            </summary>
            <Tabs groupId={`output-${name}`} queryString>
            <TabItem value="flowchart" label="Flowchart" default>
                <Mermaid value={mermaidSource}/>
            </TabItem>
            {showBpmn && (
                <TabItem value="bpmn" label="BPMN">
                    <img src={svgPath} alt={`${name} diagram`}/>
                </TabItem>
            )}
                <TabItem value="model" label="Model">
                    <CodeBlock language="json">{jsonSource}</CodeBlock>
                </TabItem>
                <TabItem value="debug" label="Debug">
                    <CodeBlock language="text">{debugSource}</CodeBlock>
                </TabItem>
            </Tabs>
        </details>
    );
};

export default OperationOutputs;
