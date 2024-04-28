import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Sequencing Operations

## Code
<Tabs groupId="flavour" queryString>
  <TabItem value="declarative" label="Declarative" default>
    ```scala file=./main/scala/workflow4s/example/docs/SequencingExample.scala start=start_declarative end=end_declarative
    ```
  </TabItem>
  <TabItem value="dynamic" label="Dynamic">    
    ```scala file=./main/scala/workflow4s/example/docs/SequencingExample.scala start=start_dynamic end=end_dynamic
    ```
  </TabItem>
</Tabs>

## BPMN

<Tabs groupId="flavour" queryString>
  <TabItem value="declarative" label="Declarative" default>
    ![and-then.svg](/../../workflows4s-example/src/test/resources/docs/and-then.svg)
  </TabItem>
  <TabItem value="dynamic" label="Dynamic">
    ![flat-map.svg](/../../workflows4s-example/src/test/resources/docs/flat-map.svg)
  </TabItem>
</Tabs>

## Model

<Tabs groupId="flavour" queryString>
  <TabItem value="declarative" label="Declarative" default>
    ```json file=./test/resources/docs/and-then.json
    ```
  </TabItem>
  <TabItem value="dynamic" label="Dynamic">
    ```json file=./test/resources/docs/flat-map.json
    ```
  </TabItem>
</Tabs>