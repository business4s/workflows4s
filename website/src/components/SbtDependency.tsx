import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import React from 'react';
import CodeBlock from '@theme/CodeBlock';


export interface SbtDependencyProps {
    moduleName: "workflows4s-core" | "workflows4s-doobie" | "workflows4s-pekko" | "workflows4s-filesystem" | "workflows4s-quartz" |
        "workflows4s-bpmn" | "workflow4s-web-api-shared" | "workflow4s-web-api-server";
    comment?: string;
}

const SbtDependency: React.FC<SbtDependencyProps> = ({moduleName, comment}) => {
    const {siteConfig} = useDocusaurusContext();
    const version = siteConfig.customFields?.workflows4sVersion;
    return (
        <CodeBlock className="language-scala">
            {`"org.business4s" %% "${moduleName}" % "${version}"${comment ? ` // ${comment}` : ''}`}
        </CodeBlock>
    );
}

export default SbtDependency;