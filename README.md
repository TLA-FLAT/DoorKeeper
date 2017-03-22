# FLAT DoorKeeper
The DoorKeeper guards the entry at a (FLAT) repository. It does so by running a SIP (_Submission Information Package_) through a sequence of actions, when one of them fails the SIP should not be allowed into the repository.

Some actions depend on some (patched) libraries which are not available in a Maven repository:
- https://github.com/menzowindhouwer/fedora-cargo-plugin.git
- https://github.com/menzowindhouwer/fedora-client.git
- https://github.com/meertensinstituut/EPICify.git

```sh
$ git clone https://github.com/menzowindhouwer/fedora-cargo-plugin.git 
$ cd fedora-cargo-plugin
$ mvn clean install
$ cd ..

$ git clone https://github.com/menzowindhouwer/fedora-client.git 
$ cd fedora-client
$ mvn clean install
$ cd ..

$ git clone https://github.com/meertensinstituut/EPICify.git  
$ cd EPICify
$ mvn clean install
$ cd ..

$ git clone https://github.com/TLA-FLAT/DoorKeeper.git
$ cd DoorKeeper
$ mvn clean install
```

## DoorKeeper command line
The DoorKeeper can be executed from the command line. But can also be embedded in a servlet (_UPCOMMING_: [ServiceFlat](https://github.com/TheLanguageArchive/FLAT/tree/develop/docker/add-doorkeeper-to-flat/flat/deposit/ServiceFLAT)).

```sh
$ java -jar target/doorkeeper.jar -?
INF: doorkeeper <OPTIONS> <workflow FILE> (<param>=<value>)*
INF: where <OPTIONS> are:
INF: -f <action> : from this action (name) (optional)
INF: -t <action> : to this action (name) (optional)
```

## A workflow
The actions to be executed are specified in a workflow XML file. Its structure reflects the basic workflow:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<flow>
    <config>
        <!-- global properties -->
    </config>
    <init>
        <!-- action flow to initialize the workflow (will be executed always) -->
    </init>
    <main>
        <!-- main action flow to be executed (execution can be partial based on from/to options) -->
    </main>
    <exception>
        <!-- action flow to be executed when the main action flow threw an exception -->
    </exception>
    <final>
        <!-- action flow to tear down the workflow (will be executed always) -->
    </final>
</flow>
```

### Configuration section 
The purpose of the configuration section is to specify global properties. These can be imported from the "environment", specified in the worflow file, or provided by the host application, e.g., the command line tool or a servlet.

```xml
    <config>
        <import class="nl.mpi.tla.flat.deposit.context.Environment" prefix="env-"/>
        <import class="nl.mpi.tla.flat.deposit.context.SystemProperties" prefix="sys-"/>
        <property name="home" value="/app/flat" uniq="true"/>
        <property name="base" value="{$home}/deposit" uniq="true"/>
        <property name="bag" value="{$base}/bags/{$sip}" uniq="true"/>
        ...
    </config>

```

In this example environment variables are imported with the prefix `env-`, e.g., `env-HOME`, and Java system properties with the prefix `sys-`. An import is handled by a Java class  (indicated by the `class` attribute), which are dynamically loaded, i.e., its easy to add a new one. An import class needs to implement the [`nl.mpi.tla.flat.deposit.context.ImportPropertiesInterface` interface](src/main/java/nl/mpi/tla/flat/deposit/context/ImportPropertiesInterface.java) and be available in the Java classpath.

Properties have a name and a value. Within curly braces (`{}` inspired by [XSLT's AVTs](https://www.w3.org/TR/xslt20/#attribute-value-templates)) an [XPath 2.0 expression](https://www.w3.org/TR/xpath20/) can be used. Previously declared properties are available as variables in these expressions and one can use the rich set of [XPath 2.0 functions and operators](https://www.w3.org/TR/xquery-operators/) if any processing is required. If needed its even possible to use and add extension functions (see [`SaxonExtensionFunctions.java`](src/main/java/nl/mpi/tla/flat/deposit/util/SaxonExtensionFunctions.java) for some examples). The `uniq` attribute is used to indicate if a property should be unique, i.e., not be declared again and if this does happen an error will be raised.

### Specifying actions
The other sections of the workflow file contain declarations of action invocations:

```xml
    <init>
        <action name="log setup" class="nl.mpi.tla.flat.deposit.action.WorkspaceLogSetup">
            <parameter name="dir" value="{$work}/logs"/>
        </action>        
        <action name="check workspace" class="nl.mpi.tla.flat.deposit.action.SIPLoad">
            <parameter name="sip" value="{$work}/metadata/record.cmdi"/>
        </action>
    </init>
```

An action is implemented by a class (indicated by the `class` attribute), which should implement the [`nl.mpi.tla.flat.deposit.action.ActionInterface` interface](src/main/java/nl/mpi/tla/flat/deposit/action/ActionInterface.java) or, more conveniently, subclass the [`nl.mpi.tla.flat.deposit.action.AbstractAction` abstract class](src/main/java/nl/mpi/tla/flat/deposit/action/AbstractAction.java) and be available on the Java classpath. Many examples are available in [src/main/java/nl/mpi/tla/flat/deposit/action](src/main/java/nl/mpi/tla/flat/deposit/action).

The action invocation will receive the specified parameters. A parameter is specified similarily to the properties in the configuration section. The values can potentially be constructed dynamically, i.e., by using the AVTs. The variables available in the AVTs are the global properties, i.e., not any parameters (from previously run actions).

## Action library
This section contains a list of currently available actions. They are not listed in alfabetical order, but in more in the order they would make sense in a workflow. Most of them are generic, but others are repostory or even institute specific and are likely to move to an own repository in the future.

### <a name="WorkspaceLogSetup"></a>`nl.mpi.tla.flat.deposit.action.WorkspaceLogSetup`

parameter | default  | cardinality | notes
----------|----------|-------------|------
`dir`     | `./logs` | ?           | directory will be created if it doesn't exist already

Will create a logback configuration that will create two log files for the workflow in the specified directory:
- `devel.log`: contains any log message issues during the worflow execution
- `user-log-events.xml`: contains any INFO, WARN and ERROR log messages (to get wellformed XML access this log file using the `user-log.xml` wrapper)

_NOTES_
- best be used a the first action in the `init` section of the workflow
- a Mapped Diagnoctic Context (_MDC_) is created so only the log messages of the thread running the workflow end up in the log files.
- see [WorkspaceLogCleanup](#WorkspaceLogCleanup) (mandatory, later)

### <a name="SIPLoad"></a>`nl.mpi.tla.flat.deposit.action.SIPLoad`

parameter | default                  | cardinality | notes
----------|--------------------------|-------------|------
`sip`     | `./metadata/record.cmdi` | ?          |

Load a [CMD record](http://www.clarin.eu/cmdi) as a SIP.

_NOTES_:
- best be used in the `init` section of the workflow, so the SIP is loaded even if only some of the main actions are requested
- the DoorKeeper interacts with a SIP through the [`nl.mpi.tla.flat.deposit.sip.SIPInterface` interface](src/main/java/nl/mpi/tla/flat/deposit/sip/SIPInterface.java), so its possible to potentially use other SIP specifications than CMDI

### <a name="PackageAssembly"></a>`nl.mpi.tla.flat.deposit.action.PackageAssembly`

parameter | default       | cardinality | notes
----------|---------------|-------------|------
`dir`     | `./resources` | ?           | directory will be created if it doesn't exist already
`prefix`  | `foo`         | ?           | handle prefix

Copies (remote) resources into the working directory.

**TODO**:
- [ ] limit the remote download locations
- [ ] limit the directories in which local resouces can live, so one can't submit a SIP that would create a security leak
- [ ] check that all resources are available and accessible

### <a name="Validate"></a>`nl.mpi.tla.flat.deposit.action.Validate`

parameter     | default   | cardinality | notes
--------------|-----------|-------------|------
`schemaCache` | `./cache` | ?           | directory will be created if it doesn't exist already
`rules`       |           | ?           | should point to a [Schematron file](http://www.schematron.com/)

Validate the SIP document against its [XML Schema](https://www.w3.org/XML/Schema), which should be specified in a `@xsi:schemaLocation`. Optionally additional validation can be done against a set of Schematron rules. Validation errors will lead to failure of the action. Both errors and warnings will be logged.

_NOTES_:
- checks any XML document against its schema, so needs a Schematron rule to check that the file has actually the right type!

### <a name="FITS"></a>`nl.mpi.tla.flat.deposit.action.FITS`

parameter     | default  | cardinality | notes
--------------|----------|-------------|------
`fitsService` |          | 1           |
`mimetypes`   |          | 1           | should point to a valid [mimetypes file](#mimetypes)

[FITS](http://projects.iq.harvard.edu/fits) is a library (also accessable via a command line tool or servlet) to identify, validate and extract technical metadata for a wide range of file formats. This action will identify and validate all resources in the SIP against a FITS server. The identified MIME type of a resource is checked against the specified list of allowed MIME types.

_NOTES_:
- if the MIME type is faulty specified by the SIP a warning will be issued and the faulty MIME type overwritten

#### <a name="mimetypes"></a>mimetypes
```xml
<?xml version="1.0" encoding="UTF-8"?>
<mimetypes>
	<mimetype value="application/pdf"/>
	<mimetype value="text/plain"/>
	<mimetype value="image/jpg"/>
</mimetypes>
```

### <a name="Persist"></a>`nl.mpi.tla.flat.deposit.action.Persist`

parameter          | default  | cardinality | notes
-------------------|----------|-------------|------
`resourcesDir`     |          | 1           | directory will be created if it doesn't exist already
`policyFile`       |          | 1           | should point to a valid [policy file](#policyFile)
`xpathDatasetName` |          | 1           | XPath 2.0 expression into the SIP XML specification

Move resources that match the rules in the policy file to a subdirectory, dynamically named using the `xpathDatasetName` in the (persistent) resource directory. 

#### <a name="policyFile"></a>policyFile
```xml
<?xml version="1.0" encoding="UTF-8"?>
<persistence-policies>
	<persistence-policy property="mimetype" regex="^.+/pdf$" target="{$dataset_name}/pdf"/>
	<persistence-policy property="mimetype" regex="^text/.+$" target="{$dataset_name}/text"/>
	<default-persistence-policy target="{$dataset_name}/default"/>
</persistence-policies>
```

_NOTES_:
- the action could be extended to match additional (technical) properties of a resource

### <a name="HandleAssignment"></a>`nl.mpi.tla.flat.deposit.action.HandleAssignment`

parameter | default  | cardinality | notes
----------|----------|-------------|------
`prefix`  | `foo`    | ?           |

Assigns handles to the SIP and its resources.

_NOTES_:
- the handles are not created by this action!
- see [EPICHandleCreation](#EPICHandleCreation) (mandatory, later)

**TODO**:
- [ ] validate that the assigned handle doesn't exist yet (how can we "reserve" a handle globally?)

### <a name="ACL"></a>`nl.mpi.tla.flat.deposit.action.ACL`

parameter | default                | cardinality | notes
----------|------------------------|-------------|------
`policy`  | `./metadata/policy.n3` | ?           | should point to a valid [WebAccessControl file](#WebAccessControl)
`dir`     | `./acl`                | ?           | directory will be created if it doesn't exist already

Converts a WebAccessControl file into a set of [XACML policies](https://wiki.duraspace.org/display/FEDORA38/XACML+Policy+Enforcement), i.e., one for each resource in the SIP, in the specified directory. It will also extract the [user profile](#userProfile) of the owner into an `owner.xml` in the specified directory. This profile can be used by subsequent actions that need info on the owner. 

#### <a name="WebAccessControl"></a>WebAccessControl

The policy file should be based on the [WebAccessControl W3C proposal](https://www.w3.org/wiki/WebAccessControl). Here are some common patterns:

- a public SIP
```n3
@prefix acl: <http://www.w3.org/ns/auth/acl#> .
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

# make the whole SIP public
[acl:accessTo <sip>; acl:mode acl:Read; acl:agentClass foaf:Agent].

#give the owner read and write access
[acl:accessTo <sip>; acl:mode acl:Read, acl:Write;  acl:agent <#owner>].

# the owner
<#owner> a foaf:Person ;
   foaf:account [foaf:accountServiceHomepage <#flat>; foaf:accountName "bob@meertens.knaw.nl"].
```
- a private SIP with a public resource
```n3
@prefix acl: <http://www.w3.org/ns/auth/acl#> .
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

# make a specific resource (identified by the ID of the ResourceProxy) in the SIP public
[acl:accessTo <sip#h1>; acl:mode acl:Read; acl:agentClass foaf:Agent].

# give the owner read and write access
[acl:accessTo <sip>; acl:mode acl:Read, acl:Write;  acl:agent <#owner>].

# the owner
<#owner> a foaf:Person ;
   foaf:account [foaf:accountServiceHomepage <#flat>; foaf:accountName "bob@meertens.knaw.nl"].
```
- a private SIP with a shared resource
```n3
@prefix acl: <http://www.w3.org/ns/auth/acl#> .
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

# make a specific resource (identified by the ID of the ResourceProxy) in the SIP accessible to a specific user
[acl:accessTo <sip#h1>; acl:mode acl:Read; acl:agent <#other1>].

# a colleague
<#other1> a foaf:Person ;
   foaf:account [foaf:accountServiceHomepage <#flat>; foaf:accountName "sarah@cmeertens.knaw.nl"].
 
# give the owner read and write access
[acl:accessTo <sip>; acl:mode acl:Read, acl:Write;  acl:agent <#owner>].

# the owner
<#owner> a foaf:Person ;
   foaf:account [foaf:accountServiceHomepage <#flat>; foaf:accountName "bob@meertens.knaw.nl"].
```
- an academic SIP
```n3
@prefix acl: <http://www.w3.org/ns/auth/acl#> .
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

# make the whole SIP available to academics
[acl:accessTo <sip>; acl:mode acl:Read; acl:agentClass <#academic>].

#give the owner read and write access
[acl:accessTo <sip>; acl:mode acl:Read, acl:Write;  acl:agent <#owner>].

# academics (= the drupal role of 'authenticated user')
<#academic> a foaf:Group;
   foaf:account [foaf:accountServiceHomepage <#flat>; foaf:accountName "authenticated user"].
   
# the owner
<#owner> a foaf:Person ;
   foaf:account [foaf:accountServiceHomepage <#flat>; foaf:accountName "bob@meertens.knaw.nl"].
```

#### <a name="userProfile"></a>User profile
```xml
<user>
  <name>me@example.com</name>
</user>
```

### <a name="CreateFOX"></a>`nl.mpi.tla.flat.deposit.action.CreateFOX`

parameter               | default                    | cardinality | notes
------------------------|----------------------------|-------------|------
`owner`                 |                            | 1           | the [user profile](#userProfile) of the owner
`fedoraConfig`          |                            | 1           | path to a valid [Fedora Commons configuration](#fedoraConfig)
`cmd2fox`               |                            | 1           | should point to a valid [XSLT 2.0 stylesheet](https://www.w3.org/TR/xslt20/)
`jar_cmd2fox`           |                            | ?           | used to resolve the include of jar:cmd2fox.xsl, which is the main cmd2fox.xsl embedded in LAT2FOX, should point to a valid [XSLT 2.0 stylesheet](https://www.w3.org/TR/xslt20/). Not needed when the cmd2fox XSL doesn't use this include!
`dir`                   | `./fox`                    | ?           | directory will be created if it doesn't exist already
`policies`              |                            | *           | should point to directories containing valid XACML policy files (see [ACL](#ACL))
`management`            |                            | *           | should point to directories containing managenent files
`icons`                 | `/app/flat/icons`          | ?           | directory where the icons are found
`collections-map`       |                            | ?           | a [collections map](#collectionsMap) to dynamically determine the collection the SIP is a member of, if there are no explicit memberships in the SIP itself
`oai-include-eval`      | `true()`                   | ?           | [XPath 2.0 expression](https://www.w3.org/TR/xpath20/) to determine if a SIP should be available via OAI-PMH
`always-collection-eval`| `false()`                  | ?           | [XPath 2.0 expression](https://www.w3.org/TR/xpath20/) to determine if a SIP should always get the `islandora:collectionCModel`, if `false` this only happens when a SIP already refers to other SIPs
`always-compound-eval`  | `false()`                  | ?           | [XPath 2.0 expression](https://www.w3.org/TR/xpath20/) to determine if a SIP should always get the `islandora:compoundCModel`, if `false` this only happens when a SIP contains resources

Uses the XSLT stylesheets, where (optionally) `jar_cmd2fox` is included into `cmd2fox` as `jar:cmd2fox.xsl`, to convert the SIP XML specification into a set of [FOXML](https://wiki.duraspace.org/pages/viewpage.action?pageId=66585857) files, which are stored into the specified directory.

If a policy file exists for the SIP and/or a resource in one of the policies directories it will be included in the FOXML.

If a management file exists for the SIP its included in the MGMT datastream. This stream is meant to contain collection management specific info, e.g., curation notes, and is only accessible for users with a _data manager_ or _administrator_ role.

_NOTES_:
- for a SIP a specific access policy based on its Fedora ID (+ `.xml`) is looked for, if it doesn't exist it fallsback to `default-cmd-policy.xml` or even `default-policy.xml`
- for a resource a specific access policy based on its Fedora ID (+ `.xml`) is looked for, if it doesn't exist it fallsback to `default-resource-policy.xml` or even `default-policy.xml`
- for a SIP a specific management file based on its Fedora ID (+ `.xml`) is looked for
- a management file should be a valid [Java XML properties](http://docs.oracle.com/javase/8/docs/api/java/util/Properties.html) file 
- see [ACL](#ACL) (optional, earlier)
- see [Deposit](#Deposit) (mandatory, later)

**TODO**:
- [ ] should be less CMDI/`jar:cmd2fox.xsl` specific, e.g., the documented parameter behaviour depends actually on the XSLT used
- [X] hide away `relations`

#### <a name="collectionsMap"></a>Collections map

Each collection in the collections map contains an [XPath 2.0 expression](https://www.w3.org/TR/xpath20/), which is evaluated against the SIP. If the evaluation returns true the SIP is a potential member of that collection. If it actually will be is determined by the mode:
- `first`: the SIP becomes a member of the first collection
- `all` (default): the SIP becomes a member of all the collections

When there is no potential collection the, optional, default collection is taken or the SIP becomes a member of the fallback `islandora:compound_collection` collection.

```xml
<map xmlns:cmd="http://www.clarin.eu/cmd/" mode="all">
    <default pid="lat:collection_meertens"/>
    <!--
Papieren collecties: 0-1000, 

Digitale collecties: 1001-1999 en 3000-e.v., 

Audio collecties: 2000 – 2999.
    -->
    <collection pid="lat:collection_paper">
        <xpath>exists(/cmd:CMD/cmd:Components/cmd:MeertensCollection/cmd:CoreCollectionInformation
            [number(cmd:collectionID) le 1000])</xpath>
    </collection>
    <collection pid="lat:collection_digital">
        <xpath>exists(/cmd:CMD/cmd:Components/cmd:MeertensCollection/cmd:CoreCollectionInformation
            [(number(cmd:collectionID) ge 1001 and number(cmd:collectionID) le 1999) or (number(cmd:collectionID) ge 3000)])</xpath>
    </collection>
    <collection pid="lat:collection_audio">
        <xpath>exists(/cmd:CMD/cmd:Components/cmd:MeertensCollection/cmd:CoreCollectionInformation
            [number(cmd:collectionID) ge 2000 and number(cmd:collectionID) le 2999])</xpath>
    </collection>
    <!-- CLARIN-NL -->
    <!-- - DBD -->
    <collection pid="hdl:10744/cff73032-1005-4347-a1f0-e840793822cd">
        <xpath>exists(/cmd:CMD/cmd:Components/cmd:DBD)</xpath>
        <policy type="resource">public-resource-policy.xml</policy>
    </collection>
    <!-- - NEHOL -->
    <collection pid="hdl:10744/e0e0253e-94d9-4d1f-90a8-4ad2464ed794">
        <xpath>exists(/cmd:CMD/cmd:Components/(cmd:lat-corpus|cmd:lat-session))</xpath>
        <policy type="resource">public-resource-policy.xml</policy>
    </collection>
    <!-- - D-LUCEA -->
    <collection pid="hdl:10744/dcd5f7ad-da56-4a73-9f57-510a628879ba">
        <xpath>exists(/cmd:CMD/cmd:Components/(cmd:collection|cmd:lucea))</xpath>
        <policy type="resource">public-resource-policy.xml</policy>
    </collection>
    <!-- Roots of Ethnolect -->
    <collection pid="hdl:10744/682ea992-b567-4c02-8f94-8291d8610ef5">
	   <xpath>exists(/cmd:CMD/cmd:Components/cmd:EthnolectConversation)</xpath>
    </collection>
</map>
```

### <a name="EasyBag"></a>`nl.mpi.tla.flat.deposit.action.EasyBag`

parameter      | default  | cardinality | notes
---------------|----------|-------------|------
`foxes`        |          | 1           |
`bags`         |          | 1           |
`creator`      |          | ?           |
`audience`     |          | ?           |
`accessRights` |          | ?           |

Creates an [EASY Bag](https://github.com/DANS-KNAW/easy-sword2) for this SIP for a backup deposit to [DANS](http://www.dans.knaw.nl/) via SWORD.

_NOTES_:
- the FOXML file is used to pick up the Dublin Core metadata generated before
- this just creates the ZIPped BAG, the actual upload to DANS is a separate (batch) process

### <a name="FedoraUser"></a>`nl.mpi.tla.flat.deposit.action.FedoraUser`

parameter         | default  | cardinality | notes
------------------|----------|-------------|------
`user`            |          | 1           | an [user profile](#userProfile)
`fedoraConfig`    |          | 1           | path to a valid [Fedora Commons configuration](#fedoraConfig)
`userFedoraConfig`|          | 1           | path to the to be created user-specific [Fedora Commons configuration](#fedoraConfig)
`drupal`          |          | 1           | Drupal directory where Islandora is installed
`drush`           |          | 1           | path to the `drush` command

Looks up the Drupal (hashed) password of the user and creates a [Fedora Commons configuration](#fedoraConfig) that allows to login as that user and thus actions take place with permissions of this user and the user will associated with them in the audit trail.

_NOTES_:
- see [Deposit](#Deposit) (optional, later)

#### <a name="fedoraConfig"></a>Fedora Commons configuration
```xml
<FedoraCommons>
  <localServer>https://localhost:8443/fedora</localServer>
  <publicServer>http://localhost/flat</publicServer>
  <userName>fedoraAdmin</userName>
  <userPass>fedora</userPass>
  <trustStore>/opt/jssecacerts</trustStore>
  <trustStorePass>changeit</trustStorePass>
</FedoraCommons>
```

### <a name="Deposit"></a>`nl.mpi.tla.flat.deposit.action.Deposit`

parameter        | default  | cardinality | notes
-----------------|----------|-------------|------
`fedoraConfig`   |          | 1           | path to a valid [Fedora Commons configuration](#fedoraConfig) 
`dir`            | `./fox`  | ?           |

Loads all the FOXML files in the specified directory and loads them into the specified Fedora Commons repository. After this the URLs for the datastreams to which the assigned handles can resolve are known.

_NOTES_:
- use the trust store if the Fedora Commons server uses HTTPS with a self signed certificate
- see [FedoraUser](#FedoraUser) (optional, earlier)
- see [HandleAssignment](#HandleAssignment) (optional, earlier)
- see [CreateFOX](#CreateFOX) (mandatory, earlier)
- see [EPICHandleCreation](#EPICHandleCreation) (optional, later)

**TODO**:
- [ ] validate the FOXML files
- [X] make it possible to load the credentials from a separate file

### <a name="EPICHandleCreation"></a>`nl.mpi.tla.flat.deposit.action.EPICHandleCreation`

parameter        | default  | cardinality | notes
-----------------|----------|-------------|------
`fedoraConfig`   |          | 1           | path to a valid [Fedora Commons configuration](#fedoraConfig) 
`epicConfig`     |          | 1           | should point to a valid [EPIC config file](#EPICconfig)
`trustStore`     |          | ?           | path to a trust store
`trustStorePass` |          | ?           | mandatory if `trustStore` is specified

Creates the assigned handles for the SIP and its resources and makes them redirect to the right datastreams stored in the Fedora Commons repository.

_NOTES_:
- use the trust store if self signed certificates are used
- to actually create handles the status in the EPIC configuration should be `production`, i.e., not `test`.
- see [HandleAssignment](#HandleAssignment) (mandatory, earlier)
- see [Deposit](#Deposit) (mandatory, earlier)

#### <a name="EPICconfig"></a>EPIC config file
```xml
<PIDService>
  <hostName>www.pidconsortium.eu</hostName>
  <URI>http://www.pidconsortium.eu/</URI>
  <HandlePrefix>12345</HandlePrefix>
  <userName>epic</userName>
  <password>test</password>
  <email>me@example.com</email>
  <status>test</status>
</PIDService>
```

### <a name="Index"></a>`nl.mpi.tla.flat.deposit.action.Index`

parameter         | default  | cardinality | notes
------------------|----------|-------------|------
`gsearchServer`   |          | 1           |
`gsearchUser`     |          | 1           |
`gsearchPassword` |          | 1           |

Triggers indexing the deposited SIP using [gsearch](https://github.com/fcrepo3/gsearch).

_NOTES_:
- see [Deposit](#Deposit) (mandatory, earlier)

### <a name="UpdateSwordStatus"></a>`nl.mpi.tla.flat.deposit.action.UpdateSwordStatus`

parameter | default  | cardinality | notes
----------|----------|-------------|------
`props`   |          | 1           | should point to a valid properties file from the SWORD deposit of the processed SIP

If the properties file is found (otherwise the action will pass by silently) the `state.label` is updated to
- `FAILED` if the deposit has failed due to an exception
- `REJECTED` if an action in the main workflow failed
- `ARCHIVED` if the workflow was succesful

_NOTES_:
- best be used as an action in the `final` section of the workflow

### <a name="WorkspaceLogCleanup"></a>`nl.mpi.tla.flat.deposit.action.WorkspaceLogCleanup`

parameter | default  | cardinality | notes
----------|----------|-------------|------

Will remove the active log setup, i.e., the MDC associated with the thread that executed the workflow.

_NOTES_:
- best be used as the last action in the `final` section of the workflow
- see [WorkspaceLogSetup](#WorkspaceLogSetup) (mandatory, earlier)

## Example workflow

Here is a complete example taken from the [FLAT DoorKeeper Docker setup](https://github.com/TheLanguageArchive/FLAT/blob/develop/docker/add-doorkeeper-to-flat/flat/deposit/flat-deposit.xml):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<flow xmlns:flat="java:nl.mpi.tla.flat">
    <config>
        <import class="nl.mpi.tla.flat.deposit.context.Environment" prefix="env-"/>
        <import class="nl.mpi.tla.flat.deposit.context.SystemProperties" prefix="sys-"/>
        <property name="fitsService" value="http://localhost:8080/fits/" uniq="true"/>
        <property name="home" value="/app/flat" uniq="true"/>
        <property name="base" value="{$home}/deposit" uniq="true"/>
        <property name="bag" value="{$base}/bags/{$sip}" uniq="true"/>
        <property name="work" value="{flat:findBagBase($bag)}" uniq="true"/>
        <property name="easy" value="{$base}/easy" uniq="true"/>
        <property name="epicPrefix" value="12345"/>
        <property name="gsearchUser" value="fgsAdmin"/>
        <property name="gsearchPassword" value="fgsAdmin"/>
        <property name="gsearchServer" value="http://localhost:8080/fedoragsearch"/>
    </config>
    <init>
        <action name="log setup" class="nl.mpi.tla.flat.deposit.action.WorkspaceLogSetup">
            <parameter name="dir" value="{$work}/logs"/>
        </action>        
        <action name="check workspace" class="nl.mpi.tla.flat.deposit.action.SIPLoad">
            <parameter name="sip" value="{$work}/metadata/record.cmdi"/>
        </action>
    </init>
    <main>
        <action name="assemble package" class="nl.mpi.tla.flat.deposit.action.PackageAssembly">
            <parameter name="dir" value="{$work}/resources"/>
            <parameter name="prefix" value="{$epicPrefix}"/>
        </action>
        <action name="validate metadata" class="nl.mpi.tla.flat.deposit.action.Validate">
            <parameter name="schemaCache" value="{$base}/cache/schemas"/>
            <parameter name="rules" value="{$base}/policies/rules.sch"/>
        </action>
        <action name="validate resources" class="nl.mpi.tla.flat.deposit.action.FITS">
        	<parameter name="fitsService" value="{$fitsService}"/>
        	<parameter name="mimetypes" value="{$base}/policies/fits-mimetypes.xml"/>
        </action>
        <action name="persist resources" class="nl.mpi.tla.flat.deposit.action.Persist">
            <parameter name="resourcesDir" value="{$work}/resources"/>
            <parameter name="policyFile" value="{$base}/policies/persistence-policy.xml"/>
            <parameter name="xpathDatasetName" value="replace(//*[name()='MdSelfLink'], 'hdl:{$epicPrefix}/','')"/>
        </action>
        <action class="nl.mpi.tla.flat.deposit.action.HandleAssignment">
            <parameter name="prefix" value="{$epicPrefix}"/>
        </action>
        <action class="nl.mpi.tla.flat.deposit.action.ACL">
            <parameter name="policy" value="{$work}/metadata/policy.n3"/>
            <parameter name="dir" value="{$work}/acl"/>
        </action>
        <action class="nl.mpi.tla.flat.deposit.action.CreateFOX">
            <parameter name="owner" value="{$work}/acl/owner.xml"/>
            <parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
            <parameter name="cmd2fox" value="{$base}/policies/cmd2dc.xsl"/>
            <parameter name="jar_cmd2fox" value="{$base}/transforms/cmd2fox.xsl"/>
            <parameter name="dir" value="{$work}/fox"/>
            <parameter name="policies" value="{$work}/acl"/>
            <parameter name="policies" value="{$home}/policies"/>
        </action>
        <action class="nl.mpi.tla.flat.deposit.action.EasyBag">
            <parameter name="bags" value="{$easy}"/>
            <parameter name="foxes" value="{$work}/fox"/>
            <parameter name="creator" value="{$base}/policies/easy-bag-creator.xml"/>
        </action>
        <action class="nl.mpi.tla.flat.deposit.action.FedoraUser">
            <parameter name="user" value="{$work}/acl/owner.xml"/>
            <parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
            <parameter name="userFedoraConfig" value="{$work}/acl/fedora-config.xml"/>
            <parameter name="drupal" value="/var/www/html/flat"/>
            <parameter name="drush"  value="/var/www/composer/vendor/drush/drush/drush"/>
        </action>
        <action class="nl.mpi.tla.flat.deposit.action.Deposit">
            <parameter name="fedoraConfig" value="{$work}/acl/fedora-config.xml"/>
            <parameter name="dir" value="{$work}/fox"/>
        </action>
        <action class="nl.mpi.tla.flat.deposit.action.EPICHandleCreation">
            <parameter name="fedoraConfig" value="{$base}/policies/fedora-config.xml"/>
            <parameter name="epicConfig" value="{$base}/policies/epic-config.xml"/>
            <parameter name="trustStore" value="/opt/jssecacerts"/>
            <parameter name="trustStorePass" value="changeit"/>
        </action>
        <action class="nl.mpi.tla.flat.deposit.action.Index">
            <parameter name="gsearchServer" value="{$gsearchServer}"/>
            <parameter name="gsearchUser" value="{$gsearchUser}"/>
            <parameter name="gsearchPassword" value="{$gsearchPassword}"/>
        </action>
    </main>
    <exception>
    </exception>
    <final>
        <action name="status" class="nl.mpi.tla.flat.deposit.action.UpdateSwordStatus">
            <parameter name="props" value="{$work}/../../deposit.properties"/>
        </action>
        <action name="log teardown" class="nl.mpi.tla.flat.deposit.action.WorkspaceLogCleanup"/>        
    </final>
</flow>
```