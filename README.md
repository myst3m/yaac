# Yaac - Yet Another Anypoint CLI

![GitHub License](https://img.shields.io/github/license/myst3m/yaac)
![GitHub Release](https://img.shields.io/github/release/myst3m/yaac)
![GitHub Workflow Status](https://img.shields.io/github/workflow/status/myst3m/yaac/CI)

**[日本語ドキュメント](README.ja.md)**

## Table of Contents

- [About](#about)
- [Features](#features)
- [Getting Started](#getting-started)
- [Usage](#usage)
- [Connected Apps](#connected-apps)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgements](#acknowledgements)

## About

This software is a CLI for MuleSoft Anypoint Platform.
MuleSoft provides their Platform API to manage the Anypoint Platform and Mule applications.
Thanks to the APIs, developers can leverage them and developer the own tools to reduce 
administration cost of every tasks.


## Motivation

MuleSoft provides CLI (anypoint-cli-v4) via npm. It is good CLI when it is used in non-interactive system as CI/CD. 
But it is a bit difficult to use with other UNIX commands like sed/awk due to the output decoration
of the command response. Therefore, I started to develop a CLI that is friendly to UNIX commands.



## Command Reference

### login
Login and save access token to local storage.
```bash
yaac login <credential-name>
```

### get
List and retrieve resources.

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `get org` | `get organization` | List business groups |
| `get env` | `get environment` | List environments |
| `get app` | `get application` | List deployed applications (RTF/CH2.0/Hybrid) |
| `get api` | `get api-instance` | List API instances |
| `get asset` | - | List Exchange assets |
| `get proxy` | - | List API proxies |
| `get gw` | `get gateway` | List Flex Gateways (Standalone + Managed) |
| `get rtf` | `get runtime-fabric` | List Runtime Fabrics |
| `get rtt` | `get runtime-target` | List all runtime targets |
| `get ps` | `get private-space` | List Private Spaces |
| `get serv` | `get server` | List on-premise servers |
| `get ent` | `get entitlement` | Get organization entitlements |
| `get np` | `get node-port` | Get available node ports |
| `get cont` | `get contract` | List API contracts |
| `get capp` | `get connected-app` | List Connected Apps |
| `get scopes` | `get scope` | List available OAuth scopes |
| `get user` | - | List users |
| `get team` | - | List teams |
| `get idp` | - | List external IdPs |
| `get cp` | `get client-provider` | List OpenID Connect client providers |
| `get metrics` | - | Get metrics from Anypoint Monitoring |

### create
Create new resources.

| Subcommand | Description |
|------------|-------------|
| `create org` | Create a business group |
| `create env` | Create an environment |
| `create api` | Create an API instance |
| `create policy` | Apply API policy |
| `create invite` | Invite a user to organization |
| `create connected-app` | Create a Connected App |
| `create cp` | Create OpenID Connect client provider |

### delete
Delete resources.

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `delete org` | `del org` | Delete a business group (`--force` deletes all resources including RTF/Private Spaces) |
| `delete app` | `del app` | Delete deployed application |
| `delete api` | `del api` | Delete an API instance |
| `delete asset` | `del asset` | Delete Exchange asset |
| `delete cont` | `del contract` | Delete API contract |
| `delete capp` | `del connected-app` | Delete Connected App |
| `delete cp` | `del client-provider` | Delete client provider |
| `delete idp-user` | - | Delete IdP user profile |
| `delete rtf` | - | Delete Runtime Fabric |
| `delete ps` | `del private-space` | Delete Private Space |

### clear
Clear resources from organization without deleting the org.

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `clear org` | - | Clear apps, APIs, gateways, secret-groups, assets (RTF/Private Spaces NOT deleted) |

### deploy
Deploy applications and proxies.

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `deploy app` | `dep app` | Deploy application to RTF/CH2.0/Hybrid |
| `deploy proxy` | `dep proxy` | Deploy API proxy to Flex Gateway |
| `deploy manifest` | `dep manifest` | Deploy multiple apps from YAML manifest |

### upload
Upload assets to Exchange.

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `upload asset` | `up asset` | Upload Mule app JAR or RAML to Exchange |

### download
Download resources.

| Subcommand | Description |
|------------|-------------|
| `download proxy` | Download API proxy as JAR |
| `download api` | Download API proxy as JAR |

### describe
Get detailed information about resources.

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `describe org` | `desc org` | Describe organization details |
| `describe env` | `desc env` | Describe environment details |
| `describe app` | `desc app` | Describe application details |
| `describe asset` | `desc asset` | Describe Exchange asset |
| `describe api` | `desc api` | Describe API instance |
| `describe capp` | `desc connected-app` | Describe Connected App scopes |

### update
Update resource configurations.

| Subcommand | Description |
|------------|-------------|
| `update app` | Update application configuration |
| `update asset` | Update Exchange asset |
| `update api` | Update API instance configuration |
| `update org` | Update organization entitlements |
| `update conn` | Update CloudHub 2.0 connection |
| `update connected-app` | Update Connected App scopes |
| `update upstream` | Update API instance upstream URI |
| `update policy` | Update API policy configuration (e.g., JWT JWKS URL) |
| `update cp` | Update client provider settings |

### config
Configure CLI settings.

| Subcommand | Alias | Description |
|------------|-------|-------------|
| `config ctx` | `config context` | Set/show default org, env, target |
| `config cred` | `config credential` | Configure Connected App credentials |
| `config cc` | `config clear-cache` | Clear cached org/env data |

### logs
View application logs.

| Subcommand | Description |
|------------|-------------|
| `logs app` | View application container logs |

### auth
External OAuth2 authentication.

| Subcommand | Description |
|------------|-------------|
| `auth azure` | Azure AD OAuth2 authorization flow |

### http
Send HTTP requests to deployed applications.
```bash
yaac http <method> <url> [options]
```


## Useful features
 - 5x faster ! than Anypoint CLI (thanks to Graal, and by caching and parallel API calls)
 - HTTP/2 support for faster API communication with Anypoint Platform
 - Output is inspired from kubectl so as to be friendly with other UNIX tools
 - Selectable output format (JSON/YAML/EDN/short)
 - Organizations and Environments meta info are cached in a local file to make better performance
 - HTTP tracing for API calls (with protocol version display)
 - Deploy and delete multiple Mule applications at one time by specifiying labels or a query option.
 - Spinner feedback during command execution for better UX



## Supported Architecture

Yaac is implemented in Clojure. Therefore , it could be packaged to Jar by AOT and native binary by Graal.

- GNU/Linux x86-64 (native binary)
- All platforms supports Java (Run with java command)


## Benchmark to Anypoint CLI v4
Yaac caches organization and environemnt IDs since it is rarely updated in regular operations.
Since it takes about 700 ~ 800ms by each API call to the control plane, it can be faster significantly.
And yaac calls APIs in parallel, it is also useful for reducing time. It is normally 5x faster thanks to Graal native-image.
Here is the sample benchmark to list application.

The first yaac execution after login is a bit slow since no cache for org and env is stored.

__Anypoint CLI v4__

```
$ time anypoint-cli-v4 runtime-mgr application list
╔══════════╤═════════╤══════════════════════════════════════╤════════════╤══════════════════════════════════════╗
║ App name │ Status  │ Target                               │ Updated    │ App ID                               ║
╟──────────┼─────────┼──────────────────────────────────────┼────────────┼──────────────────────────────────────╢
║ hello    │ APPLIED │ 738ce306-a43d-4d8c-b35b-e11bd5ad7d6c │ 6 days ago │ 17f4a6c5-2fcc-415e-90f9-190189c9e525 ║
╚══════════╧═════════╧══════════════════════════════════════╧════════════╧══════════════════════════════════════╝


real	0m6.337s
user	0m1.964s
sys	0m0.523s

```

__yaac__

T1 is the org (business group)  name and Production is the environment name.

```
$ time yaac get app T1 Production
ORG  ENV         NAME   ID                                    STATUS   STATUS   TARGET-ID                             
T1   Production  hello  17f4a6c5-2fcc-415e-90f9-190189c9e525  APPLIED  RUNNING  738ce306-a43d-4d8c-b35b-e11bd5ad7d6c  

real	0m0.921s
user	0m0.024s
sys	0m0.038s
```



## Misc
In my dev environment, it takes about 800~900ms by 1 HTTP API call.
For many yaac sub commands, it allows to use the org and env name instead of each ID to be easily. For the conversions, it takes at least 2 HTTP API calls and over 1600ms.
It is a bit patient for heavy CLI users (like me) , therefore it was necessary to improve by caching and multi-threaded API calls.


## Install

### Prerequisites
- Java 21+ with FFM (Foreign Function & Memory) support
- Clojure CLI tools: [https://clojure.org/guides/install_clojure]()

### Build from source

```bash
$ git clone https://github.com/myst3m/yaac
$ cd yaac

# Build uberjar
$ clj -T:build uber

# Run with Java
$ java --enable-native-access=ALL-UNNAMED -jar target/yaac-0.7.9.jar
```

### Bash Completion

Enable tab completion for yaac commands and options:

```bash
# Install to user's bash completion directory
mkdir -p ~/.local/share/bash-completion/completions
cp completions/yaac.bash ~/.local/share/bash-completion/completions/yaac

# Or source directly in your ~/.bashrc
echo 'source /path/to/yaac/completions/yaac.bash' >> ~/.bashrc
```

After installation, restart your shell or run `source ~/.bashrc`.

### Native Image (Recommended)

Build a native executable for faster startup (~15ms vs ~2s).

**Requirements:**
- GraalVM 25+ with native-image
- Set `GRAALVM_HOME` environment variable (default: `/opt/graal`)

```bash
# Build native image
$ clj -T:build native-image

# Install to ~/.local/bin
$ cp target/yaac-0.7.9 ~/.local/bin/yaac
```

### Build Tasks

| Task | Command | Description |
|------|---------|-------------|
| Clean | `clj -T:build clean` | Remove target directory |
| Uberjar | `clj -T:build uber` | Build executable JAR |
| Native | `clj -T:build native-image` | Build GraalVM native executable |


## Getting Started

You can download the binary in 'dist' folder and leave it in the directory on PATH.
Nothing other libraries is required.

First you should setup Connected App on Anypoint Platform and should obtain client-id and client-secret.
Then, execute the following command and input application name, client-id, client-secret and grant-type.

```bash
$ yaac config credential

```

This software asks below. 

```bash
app name: <your Connected App name>
client id: <client id of your Connected App>
client secret: <client secret of your Connected App>
grant type: <type the number>
 1. client credentials
 2. authorization code
 3. resource owner password
```
If you chooose client credentials (1), the credential file is stored in ~/.yaac/credentials .
You should change the permission of files to 600.

```bash
# ~/.yaac/credentials

{"cc-app":
 {"client_id":"abcdefaga2980",
  "client_secret":"0a993jgjaijfdj",
  "grant_type":"client_credentials"}}}
  
```
  

And if you choose authroization code, this command starts the embedded HTTP server and show the URL to get authorization code.
You can copy the URL and paste in the Browser URL field. You see Anypoint platform site and asked if you grant permission to the 
Connected App.


## Usage

### Login
```bash
$ yaac login cc-app
```

```
[{"token-type":"bearer",
  "access-token":"0cfd1d0c-dc30-4a3a-b9ef-87bb30fe53da",
  "expires-in":3600}]
```  

### Get business groups
```bash
$ yaac get org
```

```
NAME      ID                                    PARENT-NAME  
MuleSoft  376ce256-xxxx-4401-a983-d3cb09689ee0  -            
ALC       99c9409f-xxxx-40a0-b5b5-9612966a912f  MuleSoft     
T1        fe1db8fb-xxxx-4b5c-a591-06fea582f980  MuleSoft     
```

### Get Environment list of specified business group

```bash
$ yaac get env T1
```

```
NAME        ID                                    TYPE        
Design      bf2bc9ac-xxxx-4f1d-8357-eb03aa158945  design      
Production  0d0debc2-xxxx-4e41-b5fb-7911421cc2c5  production  
Sandbox     0f678523-xxxx-4098-9be9-cc542dca2e6d  sandbox     
```
### Get assets on Exchange in specified business group

```bash
$ yaac get assets T1
```

```
ORGANIZATION-ID                       GROUP-ID                              GROUP-NAME  ASSET-ID                              TYPE       VERSION  
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          app-large-db-0                        app        1.0.29   
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          mule-module-account-api               connector  0.0.2    
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          mule-plugin-account-api               extension  0.0.2    
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          account-api                           rest-api   0.0.2    
```

### Get assets by querying with search word etc...

Query with word 'account'.

```bash
$ yaac get asset -q account
```
```
ORGANIZATION-ID                       GROUP-ID                              GROUP-NAME  ASSET-ID                  TYPE       VERSION  
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          mule-module-account-api   connector  0.0.2    
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          account-api               rest-api   0.0.2    
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          accounts                  rest-api   1.0.0    
```

Additionaly, qurey with type attribute 'rest-api'

```
$ yaac get asset -q account types=rest-api
```

```
ORGANIZATION-ID                       GROUP-ID                              GROUP-NAME  ASSET-ID                  TYPE      VERSION  
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          account-api               rest-api  0.0.2    
fe1db8fb-xxxx-4b5c-a591-06fea582f980  fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1          accounts                  rest-api  1.0.0    
```


### Get apps on the specified business group and environement

```bash
yaac get app T1 Production
```
```
NAME     ID                                    STATUS    STATUS       LAST-MODIFIED-DATE  TARGET-ID                             
my-app   e849a82b-ca49-4f95-ade1-243f8e4f657e  APPLIED   RUNNING      1694598535503       738ce306-a43d-4d8c-b35b-e11bd5ad7d6c  
my-app2  c946108c-c50f-4840-abe1-6d73a1e61404  APPLYING  NOT_RUNNING  1694599901674       738ce306-a43d-4d8c-b35b-e11bd5ad7d6c  
```

### Configuration
After you use many times, you must think it is very annoyed to type a business group.
The default business group, environment and deploy target can be configured as below.

```bash
$ yaac config context T1 Production
```
You can find file ~/.yaac/config that default context is stored.
Let's see by command as below.

```bash
$ yaac config context
```
```
ORGANIZATION  ENVIRONMENT  DEPLOY-TARGET  
T1            Production   -              
```
If you configure a default deploy target, you can add that as a last argument.

```bash
$ yaac config context T1 Production ch2:cloudhub-ap-northeast-1
```

To Clear cache to retrieved Org and Env meta info again, run this sub command.

```bash
$ yaac config clear-cache
```


### Upload application

It is possible to upload Mule application packed to Jar file to Exchange.
GroupId, AssetId and version are choosed from pom.xml in Jar file.

```bash
$ yaac upload asset app-large-db.jar 

```
If GAV is overwritten if the option -g,-a and -v are specified.

```bash
$ yaac upload asset app-large-db.jar -a my-large-db-app -v 0.0.5
```

```
[{"group-id":"fe1db8fb-xxxx-xxxx-a591-06fea582f980",
  "asset-id":"my-test-app",
  "name":"my-test-app",
  "organization-id":"fe1db8fb-xxxx-xxxx-a591-06fea582f980",
  "type":"app",
  "version":"0.0.5"}]

```

### Upload RAML

The upload asset command also can handle RAML text file to upload.
The GAV options should be given.
If --api-version is not specified, API version is handled as v1.

```bash
$ yaac upload asset resources/sample/httpbin.raml -g T1 -a httpbin-test-api -v 2.0.0 --api-version=v2
```

```
[{"status":201,
  "body":
  {"group-id":"fe1db8fb-xxxx-xxxx-a591-06fea582f980",
   "asset-id":"httpbin-test-api",
   "name":"httpbin-test-api",
   "organization-id":"fe1db8fb-xxxx-xxxx-xxxx-06fea582f980",
   "type":"rest-api",
   "version":"2.0.0"}}]
```

### Deploy

RTF/CloudHub 2.0 requires to deploy from Anypoint Exchange. After upload the application, you can run as below
In this example, Deploy the app db-to-s3 on Exchange to RTF cluster named k1.
Since k1 is associated to the environment of Production on BG T1, it is possible to be successfully completed.
Deployed application name is my-app2.

```bash
$ yaac deploy app T1 Production my-app2 -g T1 -a db-to-s3 target=rtf:k1
```
```
135f8251-xxxx-447c-9c7c-4cc3ad06bd18  my-app2  7f29ee88-xxxx-471c-9d0b-e108d0b7745d  
```

You can see the deploy target by following command. It queries RTF/CloudHub 2.0 and onpremise servers.

```bash
$ yaac get runtime-target
```

```
NAME                     TYPE            ID                                    REGION          STATUS
k1                       runtime-fabric  7f29ee88-xxxx-xxxx-9d0b-e108d0b7745d  -               Active
t1ps                     private-space   738ce306-xxxx-xxxx-b35b-e11bd5ad7d6c  ap-northeast-1  Active
Cloudhub-US-East-2       shared-space    cloudhub-us-east-2                    us-east-2       Active
Cloudhub-US-East-1       shared-space    cloudhub-us-east-1                    us-east-1       Active
Cloudhub-US-West-1       shared-space    cloudhub-us-west-1                    us-west-1       Active
Cloudhub-US-West-2       shared-space    cloudhub-us-west-2                    us-west-2       Active
Cloudhub-EU-West-1       shared-space    cloudhub-eu-west-1                    eu-west-1       Active
Cloudhub-EU-West-2       shared-space    cloudhub-eu-west-2                    eu-west-2       Active
Cloudhub-AP-Southeast-1  shared-space    cloudhub-ap-southeast-1               ap-southeast-1  Active
Cloudhub-AP-Southeast-2  shared-space    cloudhub-ap-southeast-2               ap-southeast-2  Active
Cloudhub-AP-Northeast-1  shared-space    cloudhub-ap-northeast-1               ap-northeast-1  Active
Cloudhub-SA-East-1       shared-space    cloudhub-sa-east-1                    sa-east-1       Active
Cloudhub-EU-Central-1    shared-space    cloudhub-eu-central-1                 eu-central-1    Active
Cloudhub-CA-Central-1    shared-space    cloudhub-ca-central-1                 ca-central-1    Active
```

### Deploy Multiple Apps with Manifest

Deploy multiple applications at once using a YAML manifest file. This is useful for deploying entire API ecosystems (System APIs, Process APIs, Experience APIs) in a single command.

```bash
# Deploy all apps defined in manifest
$ yaac deploy manifest deploy.yaml

# Dry run - preview what would be deployed
$ yaac deploy manifest deploy.yaml --dry-run

# Deploy only specific apps
$ yaac deploy manifest deploy.yaml --only customer-sapi,order-sapi
```

#### Manifest YAML Schema

```yaml
# deploy.yaml
organization: T1
environment: Production

# Target definitions (key = actual target name from 'yaac get rtt')
targets:
  cloudhub-ap-northeast-1:      # CloudHub 2.0 shared space
    type: ch2
    instance-type: small        # nano | small | small.mem

  my-rtf-cluster:               # Runtime Fabric cluster
    type: rtf
    cpu: [500m, 1000m]          # [reserved, limit]
    mem: [1200Mi, 1200Mi]       # [reserved, limit]

  leibniz:                      # Hybrid on-premise server
    type: hybrid

# Application definitions
apps:
  # System API
  - name: customer-sapi
    asset: T1:customer-sapi:1.0.0    # group:artifact:version
    target: my-rtf-cluster
    properties:
      http.port: 8081
      db.host: oracle.internal
      db.port: 1521

  # Another System API
  - name: order-sapi
    asset: T1:order-sapi:1.2.3
    target: my-rtf-cluster
    properties:
      http.port: 8082

  # Process API (connects to System APIs)
  - name: order-papi
    asset: T1:order-papi:1.0.0
    target: cloudhub-ap-northeast-1
    properties:
      http.port: 8081
    connects-to:                     # Auto-generates connection URLs
      - customer-sapi
      - order-sapi

  # Experience API
  - name: mobile-eapi
    asset: T1:mobile-eapi:3.1.0
    target: cloudhub-ap-northeast-1
    replicas: 2                      # Override default replicas
    properties:
      http.port: 8081
    connects-to:
      - order-papi
```

#### Schema Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `organization` | string | Yes | Business group name |
| `environment` | string | Yes | Environment name |
| `targets` | map | Yes | Target definitions (key = target name) |
| `apps` | list | Yes | List of applications to deploy |

**Target fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | Yes | `rtf`, `ch2`, or `hybrid` |
| `cpu` | list | No | RTF only: [reserved, limit] e.g. `[500m, 1000m]` |
| `mem` | list | No | RTF only: [reserved, limit] e.g. `[1200Mi, 1200Mi]` |
| `instance-type` | string | No | CH2 only: `nano`, `small`, `small.mem` |
| `v-cores` | number | No | CH2 only: vCores (legacy pricing) |

**App fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Deployed application name |
| `asset` | string | Yes | Exchange asset in `group:artifact:version` format |
| `target` | string | Yes | Target name (must exist in `targets`) |
| `replicas` | int | No | Number of replicas (default: 1) |
| `properties` | map | No | Application properties (e.g. `http.port`, `db.host`) |
| `connects-to` | list | No | List of app names to connect to |

#### connects-to Feature

The `connects-to` field automatically generates connection URL properties for referenced apps:

- **RTF**: `http://<app-name>.svc.cluster.local:<port>`
- **CloudHub 2.0**: `https://<app-name>.cloudhub.io`
- **Hybrid**: `http://<app-name>:<port>`

Example: If `order-papi` has `connects-to: [customer-sapi]`, it will receive:
```
customer-sapi.url=http://customer-sapi.svc.cluster.local:8081
```

#### Generate Manifest from Mule Apps (--scan)

Automatically generate a manifest YAML by scanning a directory containing Mule applications:

```bash
# Scan current directory
$ yaac deploy manifest --scan

# Scan specific directory
$ yaac deploy manifest --scan ./system-apis

# Use custom config file location
$ yaac deploy manifest --scan . --config-properties src/main/resources/env/local.yaml

# Save to file
$ yaac deploy manifest --scan ./system-apis > deploy.yaml
```

The scan will:
1. Find all directories containing `pom.xml` with `<packaging>mule-application</packaging>`
2. Extract GAV (groupId, artifactId, version) from each pom.xml
3. Read properties from config files (searched in order):
   - `src/main/resources/config/config.yaml`
   - `src/main/resources/config/config.properties`
   - `src/main/resources/config.yaml`
   - `src/main/resources/config.properties`
   - Custom path via `--config-properties`

Example output:
```yaml
# Generated manifest from scanned Mule applications
# Found 8 apps: customer-sapi, order-sapi, ...
# TODO: Update TARGET_NAME with actual target from 'yaac get rtt'

organization: YOUR_ORG
environment: YOUR_ENV
targets:
  TARGET_NAME:
    type: ch2
    instance-type: small
apps:
- name: customer-sapi
  asset: com.example:customer-sapi:1.0.0
  target: TARGET_NAME
  properties:
    http.port: 8081
    db.host: localhost
```


## Useful Features

### HTTP Trace (Lite)

If you add "-V" option, you can see the flow of HTTP request and response.
The trace output shows the protocol version (HTTP/1.1 or HTTP/2) being used.

```bash
$ yaac get org -V
```

```
===> [0657] GET https://anypoint.mulesoft.com/accounts/api/me
<=== [0657] HTTP/2 200 OK 307ms
NAME      ID                                    PARENT-NAME
MuleSoft  376ce256-2049-4401-a983-d3cb09689ee0  -
A1        9e8908b2-d4e2-48ec-bf2c-bd5d2dd5a0fd  MuleSoft
T1        fe1db8fb-8261-4b5c-a591-06fea582f980  MuleSoft
```

yaac uses HTTP/2 by default when connecting to Anypoint Platform, which provides faster response times compared to HTTP/1.1.

### HTTP Trace (Detail)

The option "-X" is useful to see full HTTP request and response.
Since this option disable Multi-threaded API calls, the API calls appear sequentially.

When you would prefer to develop your own tools, it could be helpful to check what is sent as request.


```bash
$ yaac get env T1 Production -X
```

```
===> GET https://anypoint.mulesoft.com/accounts/api/me
Content-Type: application/json
Authorization: Bearer 03162edd-ba82-4275-a9bc-9688e9adb62b


--- No body ---


<=== 200 OK
x-anypnt-trx-id: 2341a701-8a84-4ff3-a68e-a4231fc3c451
date: Wed, 13 Sep 2023 11:47:34 GMT
x-xss-protection: 1; mode=block
x-content-type-options: nosniff
server: nginx
x-download-options: noopen
vary: Accept-Encoding
etag: W/"3390-240Ew9MGZC8LGqBX/wJCDvQgw1g"
x-dns-prefetch-control: off
cache-control: private, max-age=300, must-revalidate
content-length: 2423
x-frame-options: SAMEORIGIN
SAMEORIGIN
strict-transport-security: max-age=31536000; includeSubDomains
x-ratelimit-reset: 1694605680
content-type: application/json; charset=utf-8
x-ratelimit-limit: 400
content-encoding: gzip
x-ratelimit-remaining: 399
connection: keep-alive
x-request-id: 2341a701-8a84-4ff3-a68e-a4231fc3c451
set-cookie: _csrf=QNBXjEYXg2rKjFyJzPAbaAzg; Path=/; HttpOnly; Secure
XSRF-TOKEN=ZWCH1avy-eRb5FMrNX0QOlTHvlcsKitnFM8E; Path=/; Secure
_csrf=VZa0CNHzM9CkkmM1UhSjmnYD; Path=/; HttpOnly; Secure


{"client":
 {"client_id":"b31128d16dd94c47b77e8b8e5f1a18c5",
  "name":"c-app",
  "org_id":"376ce256-2049-4401-a983-d3cb09689ee0",
  "client_type":"control"},
   ...}

===> GET https://anypoint.mulesoft.com/accounts/api/organizations/fe1db8fb-xxxx-4b5c-a591-06fea582f980/environments
Content-Type: application/json
Authorization: Bearer 03162edd-ba82-4275-a9bc-9688e9adb62b


--- No body ---


<=== 200 OK
x-anypnt-trx-id: d3bc3a13-e510-49af-ba99-9ffdc7ed90b7
date: Wed, 13 Sep 2023 11:47:35 GMT
x-xss-protection: 1; mode=block
x-content-type-options: nosniff
server: nginx
pragma: no-cache
x-download-options: noopen
vary: Accept-Encoding
etag: W/"332-ipFd8RD3a4Xb4fYQDSHD+oyshr0"
expires: -1
x-dns-prefetch-control: off
content-length: 818
x-frame-options: SAMEORIGIN
SAMEORIGIN
strict-transport-security: max-age=31536000; includeSubDomains
x-ratelimit-reset: 1694605680
content-type: application/json; charset=utf-8
x-ratelimit-limit: 400
x-ratelimit-remaining: 399
x-geoip2-client-country: JP
connection: keep-alive
x-request-id: d3bc3a13-e510-49af-ba99-9ffdc7ed90b7
set-cookie: _csrf=uc078K4Z8wCfpRz574_hyXCQ; Path=/; HttpOnly; Secure
XSRF-TOKEN=h58D5dhV-prS18C3tye6HmtSnvEnVcVdAp_s; Path=/; Secure
_csrf=R7C9IDYLC0K_VhfqG6mKgCI3; Path=/; HttpOnly; Secure


{"data":
 [{"id":"bf2bc9ac-69eb-4f1d-8357-eb03aa158945",
   "name":"Design",
   "organizationId":"fe1db8fb-xxxx-4b5c-a591-06fea582f980",
   "isProduction":false,
   "type":"design",
   ...}]}

```   
   

### Selectable output format

It is useful to use "-o" option as below to output other format.

```bash
$ yaac get org -o json
```
```
[{"updated-at":"2023-09-12T09:52:45.780Z",
  "is-root":true,
  "client-id":"e5ef4e4232694cfbbe0672184d3cf45f",
  ...},{...},{...}]
```

Also, "-F" option can be used to choose keys to output as below

```bash
$ yaac get org -F id,name
```
```
ID                                    NAME      
376ce256-2049-4401-a983-d3cb09689ee0  MuleSoft  
99c9409f-38f8-40a0-b5b5-9612966a912f  ALC       
fe1db8fb-xxxx-4b5c-a591-06fea582f980  T1        
```

If '+' is added to key name, the key-value is added to show.
```
$ yaac get org F +org-type
```
```
NAME      ID                                    PARENT-NAME  ORG-TYPE  
MuleSoft  376ce256-2049-4401-a983-d3cb09689ee0  -            anypoint  
ALC       99c9409f-38f8-40a0-b5b5-9612966a912f  MuleSoft     anypoint  
T1        fe1db8fb-xxxx-4b5c-a591-06fea582f980  MuleSoft     anypoint  
```

### No cache

The organization list (/accounts/getme) and Enrironment list (/accounts/api/organizations/<org>/environments) is
cached to ~/.yaac/cache.
Option "-Z" disables to use the cache

## Connected Apps

Connected Apps enable secure OAuth 2.0 authentication for accessing Anypoint Platform APIs.

### Available Scopes

Get the list of all available scopes:

```bash
yaac get scopes
```

Common scopes include:
- `full` - Full access
- `read:full` - Read-only full access
- `profile` - Basic profile access
- `read:organization` - Read organization info
- `edit:organization` - Edit organization
- `read:orgenvironments` - Read environments
- `read:applications` - Read applications
- `manage:exchange` - Manage Exchange assets

### Create a Connected App

```bash
# Create a connected app with client_credentials grant
yaac create connected-app --name myapp --grant-types client_credentials --scopes profile --redirect-uris http://localhost

# Create with authorization_code grant
yaac create connected-app --name myapp --grant-types authorization_code --scopes full --redirect-uris http://localhost:8080/callback

# Create with org-level scopes (automatically assigns to current org)
yaac create connected-app --name myapp --grant-types client_credentials --redirect-uris http://localhost \
  --scopes profile --org-scopes read:organization,edit:organization,read:orgenvironments

# Create with env-level scopes for specific environments
yaac create connected-app --name myapp --grant-types client_credentials --redirect-uris http://localhost \
  --scopes profile \
  --env-scopes read:applications,create:applications \
  --org MyOrg --env "Production,Sandbox"
```

### Update Connected App Scopes

Use `update connected-app` to assign or update scopes:

```bash
# Update basic scopes for an existing connected app
yaac update connected-app myapp --scopes profile

# Assign org-level scopes (require organization context)
yaac update connected-app myapp --org-scopes read:organization,edit:organization --org MyOrg

# Assign environment-level scopes to specific environments
yaac update connected-app myapp --env-scopes read:applications --org MyOrg --env Production

# Assign env-level scopes to multiple environments
yaac update connected-app myapp --env-scopes read:applications,create:applications --org MyOrg --env "Production,Sandbox"

# Combine all scope types
yaac update connected-app myapp \
  --scopes profile \
  --org-scopes read:organization,read:orgenvironments \
  --env-scopes read:applications,delete:applications \
  --org MyOrg \
  --env "Production,Sandbox"
```

**Scope Types:**
- `--scopes` - Basic scopes with no context (e.g., `profile`, `openid`)
- `--org-scopes` - Organization-level scopes (e.g., `read:organization`, `edit:organization`)
- `--env-scopes` - Environment-level scopes (e.g., `read:applications`, `create:applications`)

**Notes:**
- `--org-scopes` requires `--org` to specify the target organization
- `--env-scopes` requires both `--org` and `--env` options
- `--env` accepts comma-separated environment names for bulk assignment
- `create:suborgs` cannot be assigned via API (invalid context parameter)
- `openid`, `email`, `offline_access` are not available for `client_credentials` grant type

### View Connected App Scopes

```bash
yaac describe connected-app myapp
```

### Delete a Connected App

```bash
yaac delete connected-app myapp
```

## API Instance Management

### Update Upstream URI

Update the upstream URI for a Flex Gateway API instance:

```bash
# Update upstream URI for API instance
yaac update upstream Org Sandbox 20671224 --upstream-uri http://172.23.0.9:8081/
```

### Update Policy Configuration

Update policy settings (e.g., JWT validation JWKS URL) by policy name:

```bash
# List policies for an API instance
yaac get policy Org Sandbox 20671224

# Update JWT policy JWKS URL
yaac update policy Org Sandbox 20671224 jwt-validation-flex --jwks-url http://172.23.0.15:8081/jwks.json
```

**Notes:**
- Policy is specified by asset-id (e.g., `jwt-validation-flex`), not numeric policy ID
- Use `yaac get policy` to find the correct policy asset-id

## Todo

- Implement many other functions



