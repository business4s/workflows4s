import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import React from 'react';
import CodeBlock from '@theme/CodeBlock';

interface SbtDependencyProps {
    moduleName: "workflows4s-core" | "workflows4s-doobie" | "workflows4s-pekko" | "workflows4s-filesystem" | "workflows4s-quartz" | "workflows4s-bpmn";
}

const SbtDependency: React.FC<SbtDependencyProps> = ({moduleName}) => {
    const {siteConfig} = useDocusaurusContext();
    const version = siteConfig.customFields?.workflows4sVersion;
    return (
        <CodeBlock className="language-scala">
            {`"org.business4s" %% "${moduleName}" % "${version}"`}
        </CodeBlock>
    );
}

export default SbtDependency;