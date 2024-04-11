import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

type FeatureItem = {
  title: string;
  Svg?: React.ComponentType<React.ComponentProps<'svg'>>;
  description: JSX.Element;
};

const FeatureList: FeatureItem[] = [
  {
    title: 'Simple',
    description: (
      <>
        Workflows4s doesn't require any dedicated servers and doesn't rely on any magical features such as
        macros or reflection.
      </>
    ),
  },
  {
    title: 'Composable',
    description: (
      <>
        Workflows are built through composing smaller blocks, similarly to how we compose IOs, streams or parser combinators.
      </>
    ),
  },
  {
    title: 'Business-oriented',
    description: (
      <>
          Workflows4s comes with built-in support for diagrams rendering and generally aims at solving business problems rather than technical ones.
      </>
    ),
  },
];

function Feature({title, Svg, description}: FeatureItem) {
  return (
    <div className={clsx('col col--4')}>
      {/*<div className="text--center">*/}
      {/*  <Svg className={styles.featureSvg} role="img" />*/}
      {/*</div>*/}
      <div className="text--center padding-horiz--md">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures(): JSX.Element {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
