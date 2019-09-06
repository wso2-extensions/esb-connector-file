# File EI Connector

The File [Connector](https://docs.wso2.com/display/EI650/Working+with+Connectors) allows you to connect to different file systems and perform various operations with the file systems. The file connector uses the apache commons VFS I/O functionalities to execute operations.
## Compatibility

| Connector version | Supported WSO2 ESB/EI version |
| ------------- |------------- |
|  [2.0.16](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.16)        |  EI 6.5.0, EI 6.4.0 |
|  [2.0.15](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.15)        |  EI 6.5.0, EI 6.4.0 |
|  [2.0.14](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.14)        |  EI 6.5.0, EI 6.4.0 |
|  [2.0.13](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.13)        |  EI 6.4.0, EI 6.1.1, ESB 5.0.0 |
|  [2.0.12](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.12)        |  EI 6.4.0, EI 6.1.1, ESB 5.0.0 |
|  [2.0.11](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.11)        |  EI 6.1.1, ESB 5.0.0 |
|  [2.0.10](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.10)        |  EI 6.1.1, ESB 5.0.0, ESB 4.9.0 |
|  [2.0.9](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.9)        |    EI 6.3.0, ESB 6.2.1|
|  [2.0.8](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.8)        |    ESB 6.2.1,ESB 6.1.1 |
|  [2.0.7](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.7)        |    ESB 6.1.1,ESB 5.0.0 |
|  [2.0.6](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.6)        |    ESB 5.0.0, ESB 4.9.0|
|  [2.0.5](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.5)        |    ESB 5.0.0, ESB 4.9.0,ESB 4.8.1|
|  [2.0.4](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.4)        |    ESB 4.9.0,ESB 4.8.1|
|  [2.0.3](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.3)        |    ESB 4.9.0,ESB 4.8.1 |
|  [2.0.2](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.2)        |    ESB 4.9.0,ESB 4.8.1 |
|  [2.0.1](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.1)        |    ESB 4.9.0, ESB 4.8.1 |
|  [2.0.0](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.0)        |    ESB 4.8.1 |
|  [1.0.0](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-1.0.0)        |    ESB 4.8.1 |


#### Download and install the connector

1. Download the connector from the [WSO2 Store](https://store.wso2.com/store/assets/esbconnector/details/3fcaf309-1a69-4edf-870a-882bb76fdaa1) by clicking the **Download Connector** button.
2. You can then follow this [documentation](https://docs.wso2.com/display/EI650/Working+with+Connectors+via+the+Management+Console) to add the connector to your WSO2 EI instance and to enable it (via the management console).
3. For more information on using connectors and their operations in your WSO2 EI configurations, see [Using a Connector](https://docs.wso2.com/display/EI650/Using+a+Connector).
4. If you want to work with connectors via WSO2 EI Tooling, see [Working with Connectors via Tooling](https://docs.wso2.com/display/EI650/Working+with+Connectors+via+Tooling).

#### Configuring the connector operations

To get started with File connector and their operations, see [Working with the File Connector](docs/topics.md).

## Building from the source

Follow the steps given below to build the File connector from the source code.

1. Get a clone or download the source from [Github](https://github.com/wso2-extensions/esb-connector-file).
2. Run the following Maven command from the `esb-connector-file` directory: `mvn clean install`.
3. The ZIP file with the File connector is created in the `esb-connector-file/target` directory.

## How you can contribute

As an open source project, WSO2 extensions welcome contributions from the community.
Check the [issue tracker](https://github.com/wso2-extensions/esb-connector-file/issues) for open issues that interest you. We look forward to receiving your contributions.
