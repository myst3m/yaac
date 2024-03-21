# Yaac - Yet Another Anypoint CLI

![GitHub License](https://img.shields.io/github/license/myst3m/yaac)
![GitHub Release](https://img.shields.io/github/release/myst3m/yaac)
![GitHub Workflow Status](https://img.shields.io/github/workflow/status/myst3m/yaac/CI)


## Table of Contents

- [About](#about)
- [Features](#features)
- [Getting Started](#getting-started)
- [Usage](#usage)
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



## Basic functions as of 2024/03/20
 - Login as a connected app (Client Credetials, Authroization code, Resource Owner Password)
 - Get Organizations
 - Get Envronments 
 - Get Applications on RTF and CloudHub 2.0
 - Get API Instances
 - Upload assets (Mule Application and RAML) to Exchange
 - Deploy applications to RTF/CloudHub2.0 
 - Delete assets on Exchange
 - Delete apps on Target (RTF, CloudHub 2.0)


## Useful features
 - 5x faster ! than Anypoint CLI (thanks to Graal, and by caching and parallel API calls)
 - Output is inspired from kubectl so as to be friendly with other UNIX tools
 - Selectable output format (JSON/YAML/EDN/short)
 - Organizations and Environments meta info are cached in a local file to make better performance
 - HTTP tracing for API calls
 - Deploy and delete multiple Mule applications at one time by specifiying labels or a query option.



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
If you are using GNU/Linux on x86-64 CPU, You can use [binary](https://github.com/myst3m/yaac/blob/main/dist/yaac-linux-x86-64) in dist folder in the tree.

This software is developoed in Clojure. You can use clojure CLI to build standalone Jar.
[https://clojure.org/guides/install_clojure]()

And build by this command.
```
$ git clone https://github.com/myst3m/yaac
$ cd yaac
$ clj -X:uberjar
```

And then you can build native image by using Graal native-image command.
[https://www.graalvm.org/22.0/reference-manual/native-image/]()

I put this bash function to .bashrc . The native-image command is expected to be installed to /opt/graal/bin in this example.

```
function ni ()
{
    /opt/graal/bin//native-image -jar $1 \
                                 -O3 \
                                 --initialize-at-run-time=com.mongodb.client.internal.Crypt,com.mongodb.internal.connection.UnixSocketChannelStream,com.mongodb.internal.connection.ZstdCompressor,com.mongodb.UnixServerAddress,com.mongodb.internal.connection.SnappyCompressor,java.sql.DriverManager \
							     --trace-class-initialization=clojure.lang.Compile \
								 -H:+BuildReport \
                                 --initialize-at-build-time \
                                 --diagnostics-mode \
							     --report-unsupported-elements-at-runtime \
				                 --gc=G1 \
				                 -Ob \
                                 --no-fallback \
                                 -J-Xmx8g \
				                 -march=native \
                                 --enable-http \
                                 --enable-https \
                                 -J-Dclojure.compiler.direct-linking=true \
                                 --report-unsupported-elements-at-runtime \
                                 --trace-object-instantiation=java.lang.Thread \
                                 -J-Dclojure.spec.skip-macros=true 

}
```
And run
```
$ ni target/yaac-0.5.3-standalone.jar
```

You can find the native image named like yaac-x.y.z-standalone in the current directory. Rename the executable command to yaac.


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
$ yaac config context T1 Production ch20:cloudhub-ap-northeast-1
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



## Useful Features

### HTTP Trace (Lite)

If you add "-V" option, you can see the flow of HTTP request and response.

```bash
$ yaac get env T1 Production -V
```

```
===> GET https://anypoint.mulesoft.com/accounts/api/me
<=== 200 OK
===> GET https://anypoint.mulesoft.com/accounts/api/organizations/fe1db8fb-xxxx-4b5c-a591-06fea582f980/environments
<=== 200 OK

NAME        ID                                    TYPE        
Design      bf2bc9ac-69eb-xxxx-xxxx-eb03aa158945  design      
Production  0d0debc2-8327-xxxx-xxxx-7911421cc2c5  production  
Sandbox     0f678523-c036-xxxx-xxxx-cc542dca2e6d  sandbox     
```

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

## Todo

- Implement many other functions



