import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import React from 'react';
import CodeBlock from '@theme/CodeBlock';
import SbtDependency, {SbtDependencyProps} from "@site/src/components/SbtDependency";


interface SbtDependenciesProps {
    deps: SbtDependencyProps[]
}

const SbtDependencies: React.FC<SbtDependenciesProps> = ({deps}) => {

    const {siteConfig} = useDocusaurusContext();
    const version = siteConfig.customFields?.workflows4sVersion;

    
    return (<CodeBlock className="language-scala">
        {deps.map((dep, idx) => (
            `"org.business4s" %% "${dep.moduleName}" % "${version}"${dep.comment ? ` // ${dep.comment}` : ''}`
        )).join('\n')
        }
    </CodeBlock>)

}

export default SbtDependencies;