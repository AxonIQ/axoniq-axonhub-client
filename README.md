# axoniq-axonhub-client

Client packages to connect to AxonHub servers.

Packages:
- axonhub-client: the main client code
- axonhub-grpc-proto: Protobuf interface definitions
- axonhub-spring-boot-autoconfigure: Autoconfiguration of Axon Framework components to 
communicate using AxonHub

## Version history

### 1.0

First release
### 1.0.1

Fix for Null handling of query result

### 1.0.3
 
- Keep Alive between client and server
- improved handling of exceptions from query handler
- updated GRPC and Netty versions
- Improved support for configuration properties in IDE when using spring-boot
