## Working with the File Connector Version 2

[[Overview]](#overview)  [[Operation details]](#operation-details) [[Sample configuration]](#sample-configuration)


### Overview

The following operations allow you to work with the file connector version 2. Click an operation name to see details on how to use it.
For a sample proxy service that illustrates how to work with the file connector, see [Sample configuration](#sample-configuration). 

| Operation        | Description |
| ------------- |-------------|
| [append](#appending-content-to-an-existing-file)    | Appends content to an existing file. |
| [archive](#archiving-a-file-or-folder)      | Archives a file or folder. |
| [copy](#copying-a-file)      | Copies a file or folder. |
| [create](#creating-a-file-or-folder)      | Creates a file or folder. |
| [delete](#deleting-a-file-or-folder)      | Deletes a file or folder. |
| [isFileExist](#checking-the-existence-of-a-file)      | Checks the existence of a file. |
| [listFileZip](#listing-all-files-inside-a-compressed-file)      | Lists all files inside zip file. |
| [move](#moving-a-file)      | Moves a file or folder. |
| [read](#reading-content-from-a-file)      | Reads content from a file. |
| [search](#searching-for-a-file)      | Finds a file based on a file pattern and directory pattern. |
| [unzip](#decompressing-a-file)      | Decompresses a zip file. |
| [ftpOverProxy](#-onnect-to-a-ftp-server-through-a-proxy)      | Connects to a FTP server through a proxy. |
| [send](#sending-a-file)      | Sends a file to a specified location. |
| [getSize](#getting-size-of-a-file)      | Returns the size of the file. |
| [getLastModifiedTime](#getting-last-modified-time-of-a-file)      | Returns last modified time of a file. |
| [splitFile](#splitting-a-file-into-multiple-chunks)      | Splits files into multiple chunks. |
| [mergeFiles](#merging-multiple-chunks-into-a-file)      | Merges files into a single file. |
| [readSpecifiedLines](#reading-specific-lines-from-a-file)      | Reads specified lines from a file. |
| [readALine](#reading-a-specific-line-from-a-file)      | Reads specified line from a file. |

### Operation details
This section provides further details on the operations related to file connector version2.

##### Appending content to an existing file 

The append operation appends content to an existing file in a specified location.

**append**
```xml
<fileconnector.append>
    <destination>{$ctx:destination}</destination>
    <inputContent>{$ctx:inputContent}</inputContent>
    <position>{$ctx:position}</position>
    <encoding>{$ctx:encoding}</encoding>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.append>
```

**Properties**
* destination: The location of the file for which content needs to be appended.
* inputContent: The content to be appended.
* position: Position to append the content. If you provide a valid position, content will be appended to that position. Otherwise, content will be appended at the end of the file.
* encoding [optional]:  The encoding that is supported. Possible values are US-ASCII ,UTF-8  and UTF-16 .
* setTimeout [optional]: The timeout value on the JSC(Java Secure Channel) session in milliseconds. e.g., 100000.
* setPassiveMode [optional]: Set to true if you want to enable passive mode.
* setSoTimeout [optional]: The socket timeout value for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Set to true if you want to use root as the user directory.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 

**Sample request**

Following is a sample REST/JSON request that can be handled by the append operation.

```json
{
     "destination":"/home/vive/Desktop/file/append.txt",
     "inputContent":"Add Append Text."
}
```
 
##### Archiving a file or folder

The archive operation archives files or folder. This operation supports the ZIP archive type.

**archive**
```xml
<fileconnector.archives>
    <source>{$ctx:source}</source>
    <destination>{$ctx:destination}</destination>
    <inputContent>{$ctx:inputContent}</inputContent>
    <fileName>{$ctx:fileName}</fileName>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.archives>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://]absolute-path,where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test   or  file:///C:/Windows ). 
* For files on a FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.txt).
* destination: The location of the archived file with the file name. (e.g., file:///home/user/test /test.zip)
* inputContent: The input content which needs to be archived.
* fileName: The name of the file where input content needs to be archived.
* setTimeout [optional]: The timeout value on the JSC(Java Secure Channel) session in milliseconds. e.g., 100000.
* setPassiveMode [optional]: Set to true if you want to enable passive mode.
* setSoTimeout [optional]: The socket timeout value for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Set to true if you want to use root as the user directory.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no.

> NOTE: To make archive operation, you can give either source or inputContent. If inputContent gives as the parameter, we need to specify fileName. Otherwise, it will use the default fileName(output.txt).

**Sample request**

Following is a sample REST/JSON request that can be handled by the archives operation.

```json
{
     "source":"/home/vive/Desktop/file",
     "destination":"/home/user/test/file.zip"
}
```

##### Copying a file

The copy operation copies files from one location to another. This operation can be used when you want to copy any kind of files and large files too, you can copy particular files with given file pattern.

**copy**
```xml
<fileconnector.copy>
    <source>{$ctx:source}</source>
    <destination>{$ctx:destination}</destination>
	<filePattern>{$ctx:filePattern}</filePattern>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
	<includeParentDirectory>{$ctx:includeParentDirectory}</includeParentDirectory>
</fileconnector.copy>
```
**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path, where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test/test.txt  or  file:///C:/Windows ). 
* For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.txt).
* destination: The location where the files should be copied to.
* filePattern [optional] : The pattern of the files to be copied. (e.g., [a-zA-Z][a-zA-Z]*.(txt|xml|jar))
* setTimeout [optional]: The timeout value on the JSC(Java Secure Channel) session in milliseconds. e.g., 100000.
* setPassiveMode [optional]: Set to true if you want to enable passive mode.
* setSoTimeout [optional]: The socket timeout value for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Set to true if you want to use root as the user directory.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 
* includeParentDirectory [optional] : Set to true if you want to include the parent directory.

**Sample request**

Following is a sample REST/JSON request that can be handled by the copy operation.

```json
{
     "source":"/home/vive/Desktop/file",
     "destination":"/home/user/test/fileCopy",
     "filePattern":".*\.xml",
     "includeParentDirectory":"false"
}
```

##### Creating a file or folder

The create operation creates a file or folder in a specified location. When creating a file, you can either create the file with content or without content.

**create**
```xml
<fileconnector.create>
    <source>{$ctx:source}</source>
    <inputContent>{$ctx:inputContent}</inputContent>
	<encoding>{$ctx:encoding}</encoding>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.create>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path, where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test/test.txt  or  file:///C:/Windows ). 
* For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.txt). For creating a file, the file path should have extension (e.g. , file:///home/user/test/test.txt)
* inputContent [optional] : The content of the file.
* encoding [optional] : The encoding that is supported. Possible values are US-ASCII ,UTF-8  and UTF-16 .
* setTimeout [optional]: The timeout value on the JSC(Java Secure Channel) session in milliseconds. e.g., 100000.
* setPassiveMode [optional]: Set to true if you want to enable passive mode.
* setSoTimeout [optional]: The socket timeout value for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Set to true if you want to use root as the user directory.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 

**Sample request**

Following is a sample REST/JSON request that can be handled by the create operation.

```json
{
     "source":"sftp://UserName:Password@Host/home/connectors/create.txt",
     "inputContent":"InputContent Text",
     "encoding":"UTF8"
}
```

##### Deleting a file or folder

The delete operation deletes a file or folder from the file system.

**delete**
```xml
<fileconnector.delete>
    <source>{$ctx:source}</source>
    <filePattern>{$ctx:filePattern}</filePattern>
	<setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.delete>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path, where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test/test.zip  or  file:///C:/Windows ). 
* For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.txt).
* filePattern: The pattern of the files to be deleted.(e.g., [a-zA-Z][a-zA-Z]*.(txt|xml|jar)).
* setTimeout [optional]: Sets the timeout value on Jsch(Java Secure Channel) session. e.g., 100000.
* setPassiveMode [optional]: Sets the passive mode to enter into passive mode. e.g., true.
* setSoTimeout [optional]: Sets the socket timeout for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Sets the whether to use the user directory as root. e.g., flase.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 

**Sample request**

Following is a sample REST/JSON request that can be handled by the delete operation.

```json
{
     "source":"/home/vive/Desktop/file",
     "filePattern":".*\.txt"
}
```

##### Checking the existence of a file

The isFilleExist   operation checks the existence of a file in a spacified location. This operation returns true if the file exists and returns false if the file does not exist in the specified location.

**isFileExist**
```xml
<fileconnector.isFileExist>
    <source>{$ctx:source}</source>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.isFileExist>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path, where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test/test.zip  or  file:///C:/Windows ). 
* For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.txt).
* setTimeout [optional]: Sets the timeout value on Jsch(Java Secure Channel) session. e.g., 100000.
* setPassiveMode [optional]: Sets the passive mode to enter into passive mode. e.g., true.
* setSoTimeout [optional]: Sets the socket timeout for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Sets the whether to use the user directory as root. e.g., flase.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 

**Sample request**

Following is a sample REST/JSON request that can be handled by the isFileExist operation.

```json
{
     "source":"/home/vive/Desktop/file/test.txt"
}
```

##### Listing all files inside a compressed file

The listFileZip operation lists all the file paths inside a compressed file. This operation supports the ZIP archive type.

**listFileZip**
```xml
<fileconnector.listFileZip>
    <source>{$ctx:source}</source>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.listFileZip>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path, where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test/test.zip  or  file:///C:/Windows ). 
* For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.zip).
* setTimeout [optional]: Sets the timeout value on Jsch(Java Secure Channel) session. e.g., 100000.
* setPassiveMode [optional]: Sets the passive mode to enter into passive mode. e.g., true.
* setSoTimeout [optional]: Sets the socket timeout for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Sets the whether to use the user directory as root. e.g., flase.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 

**Sample request**

Following is a sample REST/JSON request that can be handled by the listFileZip operation.

```json
{
     "source":"/home/vive/Desktop/file/test.zip"
}
```

##### Moving a file

The  move operation moves a file or folder from one location to another.

>Info: The move operation can only move a file/folder within the same server. For example, you can move a file/folder from one local location to another local location, or from one remote location to another remote location on the same server. You cannot use the move operation to move a file/folder between different servers. If you want to move a file/folder from a local location to a remote location or vice versa, use the copy operation followed by delete operation instead of using the move operation.

**move**
```xml
<fileconnector.move>
    <source>{$ctx:source}</source>
    <destination>{$ctx:destination}</destination>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
    <filePattern>{$ctx:filePattern}</filePattern>
	<includeParentDirectory>{$ctx:includeParentDirectory}</includeParentDirectory>
</fileconnector.move>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system, or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test  or  file:///C:/Windows ). 
* For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.txt).
* destination: The location where the file has to be moved to.
* setTimeout [optional]: The timeout value on the JSC(Java Secure Channel) session in milliseconds. e.g., 100000.
* setPassiveMode [optional]: Set to true if you want to enable passive mode.
* setSoTimeout [optional]: The socket timeout value for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Set to true if you want to use root as the user directory.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 
* filePattern [optional] : The pattern of the files to be copied. (e.g [a-zA-Z][a-zA-Z]*.(txt|xml|jar))
* includeParentDirectory [optional] : Set to true if you want to include the parent directory.

**Sample request**

Following is a sample REST/JSON request that can be handled by the move operation.

```json
{
     "source":"/home/vive/Desktop/file",
     "destination":"/home/vive/Desktop/move",
     "filePattern":".*\.txt",
     "includeParentDirectory":"true"
}
```

##### Reading content from a file

The read operation reads content from an existing file in a specified location.

**read**
```xml
<fileconnector.read>
    <source>{$ctx:source}</source>
    <filePattern>{$ctx:filePattern}</filePattern>
    <contentType>{$ctx:contentType}</contentType>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.read>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path, where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test/test.xml  or  file:///C:/Windows/me.txt ). For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.txt).
* filePattern: The pattern of the file to be read.
* contentType: Content type of the files processsed by the connector. 
* streaming [optional]: The streaming mode, This can be either true or false.
* setTimeout [optional]: Sets the timeout value on Jsch(Java Secure Channel) session. e.g., 100000.
* setPassiveMode [optional]: Sets the passive mode to enter into passive mode. e.g., true.
* setSoTimeout [optional]: Sets the socket timeout for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Sets the whether to use the user directory as root. e.g., flase.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 

>**Info :** To enable streaming for large files, you have to add the following message builder and formatter in the <ESB_HOME>/repository/conf/axis2/axis2.xml file:
            Add <messageFormatter contentType="application/file"
                    class="org.wso2.carbon.relay.ExpandingMessageFormatter"/> under message formatters.
            Add  <messageBuilder contentType="application/file"
                    class="org.apache.axis2.format.BinaryBuilder"/> under message builders.
                    

**Sample request**

Following is a sample REST/JSON request that can be handled by the read operation.

```json
{
     "source":"/home/vive/Desktop/file",
     "contentType":"application/xml",
     "filePattern":".*\.xml",
     "streaming":"false"
}
```

##### Searching for a file

The search operation finds a file or folder based on a given file pattern, directory pattern in a specified location.

**search**
```xml
<fileconnector.search>
    <source>{$ctx:source}</source>
    <filePattern>{$ctx:filePattern}</filePattern>
	<recursiveSearch>{$ctx:recursiveSearch}</recursiveSearch>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.search>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path, where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test/  or  file:///C:/Windows ). 
* For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/).
* filePattern: The pattern of the file to be searched (e.g., [a-zA-Z][a-zA-Z]*.(txt|xml|jar)).
* recursiveSearch: Whether you are searching recursively (The possible values are True or False).
* setTimeout [optional]: Sets the timeout value on Jsch(Java Secure Channel) session. e.g., 100000.
* setPassiveMode [optional]: Sets the passive mode to enter into passive mode. e.g., true.
* setSoTimeout [optional]: Sets the socket timeout for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Sets the whether to use the user directory as root. e.g., flase.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 

**Sample request**

Following is a sample REST/JSON request that can be handled by the search operation.

```json
{
     "source":"/home/vive/Desktop/file",
     "filePattern":".*\.xml",
     "recursiveSearch":"true"
} 
```
##### Decompressing a file

The unzip operation decompresses zip file.This operation supports ZIP archive type.

**unzip**
```xml
<fileconnector.unzip>
    <source>{$ctx:source}</source>
    <destination>{$ctx:destination}</destination>
    <setTimeout>{$ctx:setTimeout}</setTimeout>
    <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
    <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
    <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
    <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
</fileconnector.unzip>
```

**Properties**

* source: The location of the file. This can be a file on the local physical file system or a file on an FTP server. 
* For local files, the URI format is [file://] absolute-path, where absolute-path is a valid absolute file name for the local platform. UNC names are supported under Windows (e.g., file:///home/user/test/test.zip or  file:///C:/Windows/test.zip ). For files on an FTP server, the URI format is ftp://[ username[: password]@] hostname[: port][ relative-path]  (e.g., ftp://myusername:mypassword@somehost/pub/downloads/test.zip).
* destination: The location of the decompressed file.
* setTimeout [optional]: Sets the timeout value on Jsch(Java Secure Channel) session. e.g., 100000.
* setPassiveMode [optional]: Sets the passive mode to enter into passive mode. e.g., true.
* setSoTimeout [optional]: Sets the socket timeout for the FTP client. e.g., 100000.
* setUserDirIsRoot [optional]: Sets the whether to use the user directory as root. e.g., flase.
* setStrictHostKeyChecking [optional]: Sets the host key checking to use .e.g., no. 

**Sample request**

Following is a sample REST/JSON request that can be handled by the unzip operation.

```json
{
     "source":"/home/vive/Desktop/file/test.zip",
     "destination":"/home/vive/Desktop/file/test"
}
```

##### Connect to a FTP server through a Proxy

The ftpOverProxy operation connects to a ftp server through a proxy.

**ftpOverProxy**
```xml
<fileconnector.ftpOverProxy>
    <proxyHost>{$ctx:proxyHost}</proxyHost>
    <proxyPort>{$ctx:proxyPort}</proxyPort>
    <proxyUsername>{$ctx:proxyUsername}</proxyUsername>
    <proxyPassword>{$ctx:proxyPassword}</proxyPassword>
    <ftpUsername>{$ctx:ftpUsername}</ftpUsername>
    <ftpPassword>{$ctx:ftpPassword}</ftpPassword>
    <ftpServer>{$ctx:ftpServer}</ftpServer>
    <ftpPort>{$ctx:ftpPort}</ftpPort>
    <targetPath>{$ctx:targetPath}</targetPath>
    <targetFile>{$ctx:targetFile}</targetFile>
    <keepAliveTimeout>{$ctx:keepAliveTimeout}</keepAliveTimeout>
    <controlKeepAliveReplyTimeout>{$ctx:controlKeepAliveReplyTimeout}</controlKeepAliveReplyTimeout>
    <binaryTransfer>{$ctx:binaryTransfer}</binaryTransfer>
    <localActive>{$ctx:localActive}</localActive>
</fileconnector.ftpOverProxy>
```

**Properties**

* proxyHost: The host name of the proxy.
* proxyPort: The port number of the proxy.
* proxyUsername: The user name of the proxy.
* proxyPassword: The password of the proxy.
* ftpUsername: The username of the FTP server.
* ftpPassword: The password of the FTP server.
* ftpServer: The FTP server name.
* ftpPort: The port number of the FTP server.
* targetPath: The target path. For example, if the file path is ftp://myusername:mypassword@somehost/pub/downloads/testProxy.txt, the targetPath will be pub/downloads/
* targetFile: The name of the file.(e.g., If the path is like "ftp://myusername:mypassword@somehost/pub/downloads/testProxy.txt", then targetPath will be "testProxy.txt")
* keepAliveTimeout  [optional]: The time to wait between sending control connection keep alive messages when processing file upload or download.
* controlKeepAliveReplyTimeout  [optional]: The time to wait for control keep-alive message replies.
* binaryTransfer  [optional]: Set the file type to be transferred.
* localActive [optional]: Set the current data connection mode to either ACTIVE_LOCAL_DATA_CONNECTION_MODE or PASSIVE_LOCAL_DATA_CONNECTION_MODE. 

**Sample request**

Following is a sample REST/JSON request that can be handled by the ftpOverProxy operation.

```json
{
     "proxyHost":"SampleProxy",
     "proxyPort":"3128",
     "proxyUsername":"wso2",
     "proxyPassword":"Password",
     "ftpUsername":"master",
     "ftpPassword":"Password",
     "ftpServer":"192.168.56.6",
     "ftpPort":"21",
     "targetFile":"/home/master/res"
} 
```

##### Sending a file

The send operation sends a file to a specified location.

**send**
```xml
<fileconnector.send>
    <address>{$ctx:address}</address>
	<append>{$ctx:append}</append>
</fileconnector.send>
```

**Properties**
* address: The address where the file has to be sent.
* append: Set this to true if you want to append the response to the response file. 

> **Note :** To send a VFS file, you have to specify the following properties in your configuration:
             <property name="OUT_ONLY" value="true"/>
             <property name="ClientApiNonBlocking" value="true" scope="axis2" action="remove"/>
             
**Sample request**

Following is a sample REST/JSON request that can be handled by the send operation.

```json
{
     "address":"/home/vive/Desktop/file/outTest",
     "append":"true"
} 
```

##### Getting size of a file

The getSize operation returns the size of a file.

**getSize**
```xml
<fileconnector.getSize>
    <source>{$ctx:source}</source>
</fileconnector.getSize>
```

**Properties**
* source: The location of the file.

**Sample request**

Following is a sample REST/JSON request that can be handled by the getSize operation.

```json
{
     "source":"/home/vive/Desktop/file/outTest/sample.txt"
}
```

##### Getting last modified time of a file

The getLastModifiedTime operation returns last modified time of a file/folder.

**getLastModifiedTime**
```xml
<fileconnector.getLastModifiedTime>
    <source>{$ctx:source}</source>
</fileconnector.getLastModifiedTime>
```

**Properties**
* source: The location of the file.

**Sample request**

Following is a sample REST/JSON request that can be handled by the getLastModifiedTime operation.

```json
{
     "source":"/home/vive/Desktop/file/outTest/sample.txt"
} 
```

##### Splitting a file into multiple chunks
     
The splitFile operation splits a file into multiple chunks.

**splitFile**
```xml
<fileconnector.splitFile>
    <source>{$ctx:source}</source>
    <destination>{$ctx:destination}</destination>
    <chunkSize>{$ctx:chunkSize}</chunkSize>
	<numberOfLines>{$ctx:numberOfLines}</numberOfLines>
	<xpathExpression>{$ctx:xpathExpression}</xpathExpression>
</fileconnector.splitFile>
```

**Properties**
* source: The location of the file.
* destination: The location to write the files.
* chunkSize: The chunk size in bytes to split the file. This is to split the file based on chunk size. You should provide either chunkSize or numberOfLines to split the file.
* numberOfLines: The number of line per file. This is to split the file based on the number of lines. You should provide either chunkSize or numberOfLines to split the file.
* xpathExpression: Defines a pattern in order to select a set of nodes in xml document

**Sample request**

Following is a sample REST/JSON request that can be handled by the splitFile operation.

```json
{
     "source":"/home/vive/Desktop/file/outTest/sample.txt",
     "destination":"/home/vive/Desktop/file/outTest/",
     "chunkSize":"4096",
     "xpathExpression":"//products/product"
}
```

##### Merging multiple chunks into a file

The mergeFiles operation merges multiple chunks into a single file.

**mergeFiles**
```xml
<fileconnector.mergeFiles>
    <source>{$ctx:source}</source>
    <destination>{$ctx:destination}</destination>
    <filePattern>{$ctx:filePattern}</filePattern>
</fileconnector.mergeFiles>
```

**Properties**
* source: The location of the file.
* destination: The location to write the files.
* filePattern: The pattern of the file to be read.

**Sample request**

Following is a sample REST/JSON request that can be handled by the mergeFiles operation.

```json
{
     "source":"/home/vive/Desktop/file/outTest/",
     "destination":"/home/vive/Desktop/file/outTest/sample.txt",
     "filePattern":"*.txt*"
} 
```

##### Reading specific lines from a file

The readSpecifiedLines operation reads specific lines between given line numbers from a file.

**readSpecifiedLines**
```xml
<fileconnector.readSpecifiedLines>
    <source>{$ctx:source}</source>
    <contentType>{$ctx:contentType}</contentType>
    <start>{$ctx:start}</start>
    <end>{$ctx:end}</end>
</fileconnector.readSpecifiedLines>
```

**Properties**
* source: The location of the file.
* contentType: Content type of the files processed by the connector.
* start: Read from this line number.
* end: Read up to this line number.

**Sample request**

Following is a sample REST/JSON request that can be handled by the readSpecifiedLines operation.

```json
{
     "source":"/home/vive/Desktop/file/outTest/sampleText.txt",
     "start":"5",
     "end":"25"
} 
```

##### Reading a specific line from a file

The readALine operation reads a specific line from a file.

**readALine**
```xml
<fileconnector.readALine>
    <source>{$ctx:source}</source>
    <lineNumber>{$ctx:lineNumber}</lineNumber>
</fileconnector.readALine>
```

**Properties**
* source: The location of the file.
* lineNumber: Line number to read.

**Sample request**

Following is a sample REST/JSON request that can be handled by the readALine operation.

```json
{
     "source":"/home/vive/Desktop/file/outTest/sampleText.txt",
     "lineNumber":"5"
} 
```
### Sample configuration

Following is a sample proxy service that illustrates how to connect to the File connector and use the  create  operation to create a file.  You can use this sample as a template for using other operations in this category.

**Sample Proxy**
```xml
<proxy xmlns="http://ws.apache.org/ns/synapse"
       name="FileConnector_create"
       transports="https,http"
       statistics="disable"
       trace="disable"
       startOnLoad="true">
   <target>
      <inSequence>
         <property name="source" expression="json-eval($.source)"/>
         <property name="inputContent" expression="json-eval($.inputContent)"/>
         <property name="encoding" expression="json-eval($.encoding)"/>
         <property name="setTimeout" expression="json-eval($.setTimeout)"/>
         <property name="setPassiveMode" expression="json-eval($.setPassiveMode)"/>
         <property name="setSoTimeout" expression="json-eval($.setSoTimeout)"/>
         <property name="setStrictHostKeyChecking"
                   expression="json-eval($.setStrictHostKeyChecking)"/>
         <property name="setUserDirIsRoot" expression="json-eval($.setUserDirIsRoot)"/>
         <fileconnector.create>
            <source>{$ctx:source}</source>
            <inputContent>{$ctx:inputContent}</inputContent>
            <encoding>{$ctx:encoding}</encoding>
            <setTimeout>{$ctx:setTimeout}</setTimeout>
            <setPassiveMode>{$ctx:setPassiveMode}</setPassiveMode>
            <setSoTimeout>{$ctx:setSoTimeout}</setSoTimeout>
            <setUserDirIsRoot>{$ctx:setUserDirIsRoot}</setUserDirIsRoot>
            <setStrictHostKeyChecking>{$ctx:setStrictHostKeyChecking}</setStrictHostKeyChecking>
         </fileconnector.create>
         <respond/>
      </inSequence>
   </target>
   <description/>
</proxy>         
```






    
    
    



