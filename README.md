# File EI Connector

The File [Connector](https://docs.wso2.com/display/EI650/Working+with+Connectors) allows you to connect to different file systems and perform various operations with the file systems. The file connector uses the apache commons VFS I/O functionalities to execute operations.
## Compatibility

| Connector version | Supported WSO2 ESB/EI version |
| ------------- |------------- |
|  [3.0.3](https://github.com/wso2-extensions/esb-connector-file/tree/release-3.0.3)        |  EI 6.5.0, EI 6.4.0, EI 6.6.0, EI 7.0.x |
|  [3.0.2](https://github.com/wso2-extensions/esb-connector-file/tree/release-3.0.2)        |  EI 6.5.0, EI 6.4.0, EI 6.6.0, EI 7.0.x |
|  [3.0.1](https://github.com/wso2-extensions/esb-connector-file/tree/release-3.0.1)        |  EI 6.5.0, EI 6.4.0, EI 6.6.0, EI 7.0.x |
|  [3.0.0](https://github.com/wso2-extensions/esb-connector-file/tree/release-3.0.0)        |  EI 6.5.0, EI 6.4.0, EI 6.6.0, EI 7.0.x |
|  [2.0.23](https://github.com/wso2-extensions/esb-connector-file/tree/release-2.0.23)        |  EI 6.5.0, EI 6.4.0, EI 6.6.0, EI 7.0.x |
|  [2.0.22](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.22)        |  EI 6.5.0, EI 6.4.0, EI 6.6.0, EI 7.0.x |
|  [2.0.21](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.21)        |  EI 6.5.0, EI 6.4.0 |
   [2.0.20](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.20)        |  EI 6.5.0, EI 6.4.0 |
|  [2.0.19](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.19)        |  EI 6.5.0, EI 6.4.0 |
|  [2.0.18](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.18)        |  EI 6.5.0, EI 6.4.0 |
|  [2.0.17](https://github.com/wso2-extensions/esb-connector-file/tree/org.wso2.carbon.connector.fileconnector-2.0.17)        |  EI 6.5.0, EI 6.4.0 |
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


## Documentation

Please refer to documentation [here](https://ei.docs.wso2.com/en/latest/micro-integrator/references/connectors/file-connector/file-connector-overview/).

## Building from the source

Follow the steps given below to build the File connector from the source code.

1. Get a clone or download the source from [Github](https://github.com/wso2-extensions/esb-connector-file).
2. Run the following Maven command from the `esb-connector-file` directory: `mvn clean install`.
3. The ZIP file with the File connector is created in the `esb-connector-file/target` directory.

## How you can contribute

As an open source project, WSO2 extensions welcome contributions from the community.
Check the [issue tracker](https://github.com/wso2-extensions/esb-connector-file/issues) for open issues that interest you. We look forward to receiving your contributions.
