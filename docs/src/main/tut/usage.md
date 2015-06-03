---
layout: default
title:  "Usage"
section: "usage"
---

# Getting started

<a name="getting-started"></a>

First up you need to add the dependency for the monitoring library to your `build.scala` or your `build.sbt` file:

````
libraryDependencies += "oncue.knobs" %% "core" % "x.x.+"
````

(check for the latest release [on Bintray](https://bintray.com/oncue/releases/knobs/view)).

Once you have the dependency added to your project and SBT `update` downloaded JAR, you're ready to start adding configuration knobs to your project!

<a name="resources"></a>

# Configuration Resources

In the general case, configurations are loaded using the `load` method in the `knobs` package. Configurations are loaded from `Resource`s. A `Resource` is an abstract concept to model arbitrary locations from which a configuration source can be loaded. At the time of writing, the following `Resource` implementations were supported:

  * `FileResource` - loads a file from the file system.
  * `URLResource` - loads any URI supported by the class `java.net.URI`.
  * `ClassPathResource` - loads a file from the classpath.
  * `SysPropsResource` - loads system properties matching a specific pattern.
  * `Zookeeper`* - loads the specified zookeeper znode tree (including children from the specified location).

`*` requires the "zookeeper knobs" dependency in addition to knobs core.

Resources can be declared `Required` or `Optional`. Attempting to load a file that does not exist after having declared it a `Required` configuration `Resource` will result in an exception. It is not an error to try to load a nonexistent file if that file is marked `Optional`.

Every interaction with *Knobs* will result in a `Config` enclosed in a `scalaz.concurrent.Task` monad. Until this task is executed (see the Scalaz documentation for the exact semantics) the resources are loaded and the configuration is parsed. You can get the `Config` out of it with `cfg.run`, but this is not the recommended usage pattern. The recommended way of accessing the contents of a `Task` is to use its `map` and `flatMap` methods (more on this in the specific resource usage and best practices later in this document)

## Classpath Resources

To require the file "foo.cfg" from the classpath:

```
import knobs.{Required,ClassPathResource,Config}
import scalaz.concurrent.Task

val cfg: Task[Config] = knobs.loadImmutable(
  Required(ClassPathResource("foo.cfg", getClass.getClassLoader)))
```

This of course assumes that the `foo.cfg` file is located in the root of the classpath (`/`). If you had a file that was not in the root, you could simply do something like:

```
import knobs.{Required,ClassPathResource,Config}
import scalaz.concurrent.Task

val cfg: Task[Config] = knobs.loadImmutable(
  	Required(ClassPathResource("subfolder/foo.cfg", getClass.getClassLoader)))
```

Classpath resources are always immutable. They can technically be reloaded, but this generally does not serve any use what-so-ever as the file that is being loaded exists inside the application JAR so is a fixed entity at deploy time.


## File Resources

`File` resources are probably the most common type of resource you might want to interact with. Here's an example:

```
import java.io.File
import knobs.{Required,FileResource,Config}
import scalaz.concurrent.Task

val cfg: Task[Config] = knobs.loadImmutable(
  	Required(FileResource(new File("/path/to/foo.cfg"))))
```

On-disk files can be reloaded ([see below](#reloading) for information about reloading)

## System Property Resources

Whilst its rare to use Java system properties to set values, there may be occasions where you want to use them (perhaps as an override of a specific key). Here's an example:

```
import knobs.{Required,SysPropsResource,Config,Prefix}
import scalaz.concurrent.Task

val cfg: Task[Config] = knobs.loadImmutable(
  	Required(SysPropsResource(Prefix("oncue"))))
```

Given that system properties are just keys and value pairs, *Knobs* provides a couple of different `Pattern`s that you can use to match on the key name:

* `Exact`: the exact name of the system property you want to load. Useful when you want one specific key.
* `Prefix`: In the case where you want to load multiple system properties, you can do so using a prefix; knobs will then go an load all the system properties with that prefix name.

## Zookeeper

The zookeeper support can be used in two different styles, depending on your application design you can choose the implementation that best suits your specific style or requirements. Eitherway, ensure you add the following dependency to your project:

```
libraryDependencies += "oncue.knobs" %% "zookeeper" % "x.x.+"
```

This will put the Zookeeper knobs supporting classes into your project classpath. Regardless of which type of connection management you choose (see below), the mechanism for defining the resource is the same:

```
import knobs._

// `r` is provided by the connection management options below
knobs.load(Required(r))

```

### Configuring Zookeeper Knobs with Knobs

The Zookeeper module of knobs is itself configured using knobs (the author envisions the reader giving high-fives all around after reading that); this is simply to provide a location for the Zookeeper cluster. There is a default shipped inside the knobs zookeeper JAR, so if you do nothing, the system, will use the following (default) configuration:

```
zookeeper {
  connection-string = "localhost:2181"
  path-to-config = "/knobs.cfg"
}

```

Typically speaking this configuration will be overridden at deployment time and the right zookeeper cluster location will be provided.

### Functional Connection Management

For the functional implementation, you essentially have to build your application within the context of the `Task[A]` that contains the connection to Zookeeper (thus allowing real-time updates to the configuration). If you're dealing with an impure application such as *Play!*, its horrific use of mutable state will basically make this impossible and you'll need to use the imperative alternative.

```
import knobs.{Zookeeper,Required}

ZooKeeper.withDefault { r => for {
  cfg <- load(Required(r))

  // Application code here

} yield () }.run

```


### Imperative Connection Management

Sadly, for most systems using any kind of framework, you'll likely have to go with the imperative implementation to knit knobs correctly into the application lifecycle.

```
import knobs.{Zookeeper,Required}

// somewhere at the edge of the world call this function
// to connect to zookeeper. Connection will then stay open
// up until the point the close task is executed.
val (r, close) = ZooKeeper.unsafeDefault

// Application code here
val cfg = knobs.load(Required(r))

// then at some time later (whenever, essentially) close
// the connection to zookeeper when you wish to shut
// down application.
close.run

```

Where possible, do try and design your applications as `Free[A]` or `Kleisli[Task,YourConfig,C]` so you can actually use the functional style. The author appreciates that this is unlikely to be the common case from day one.

<a name="reading"></a>

# Reading Values

Once you have a `Config` instance, and you want to lookup some values from said configuration, the API is fortunately very simple. Consider the following example:

```
// load some configuration
val config: Task[Config] =
  knobs.loadImmutable(Required(FileResource(...))) or
  knobs.loadImmutable(Required(ClassPathResource(...)))

// do something with
val connection: Task[Connection] =
  for {
    cfg <- config
    usr = cfg.require[String]("db.username")
    pwd = cfg.require[String]("db.password")
    prt = cfg.lookup[String]("db.port")
  } yield Connection(usr,pwd,port)

```

There are two APIs at play here:

* `require`: attempts to lookup the value defined by the key and convert it into the specified `A` - a `String` in this example.

* `lookup`: the same conversion semantics as `require` with the addition that the function returns `Option[A]`. If the key is for some reason not defined, or the value could not properly be converted into the specified type, the function yields `None`.


Typically you will want to use `lookup` more than you use `require`, but there are of course valid use cases for `require`, such as in this example: if this were a data base application and the connection to the database was not properly configured, the whole application is broken anyway so it might as well error out.

In addition to these general purposes lookup APIs, `Config` has two other useful functions:

* `++`: allows you to add two configuration objects together; this can be useful if you're loading multiple configurations from different sources which is so often the case.

* `subconfig`: given a `Config` instance you can get a new `Config` instance with only keys that satisfy a given predicate. This is really useful if - for example - you only wanted to collect keys in the "foo" section of the configuration: `cfg.subconfig("foo")`.

<a name="aws"></a>

# AWS Configuration

If you're running *Knobs* from within an application that is hosted on AWS, you're in luck!... *Knobs* comes with automatic support for learning about its surrounding environment and can provide a range of useful configuration settings. Consider the following example:

```
import knobs._

val c1: Task[Config] =
  knobs.loadImmutable(Required(FileResource(...)))

val cfg: Task[Config] =
  c1.flatMap(AWS.config)

```

This simple statement adds the following configuration keys to the in-memory configuration:

<table>
  <thead>
    <tr>
      <td>Key</td>
      <td>Data type</td>
      <td>Description</td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>aws.user-data</td>
      <td>Config</td>
      <td>Dynamically embed Knobs configuration format strings in the AWS instance user-data and knobs will extract that and graft it to the running Config.</td>
    </tr>
    <tr>
      <td>aws.security-groups</td>
      <td>Seq[String]</td>
      <td>The AWS-assigned reference for this AMI.</td>
    </tr>
    <tr>
      <td>aws.meta-data.instance-id</td>
      <td>String</td>
      <td>The AWS-assigned reference for this instance.</td>
    </tr>
    <tr>
      <td>aws.meta-data.ami-id</td>
      <td>String</td>
      <td>The AWS-assigned reference for this AMI.</td>
    </tr>
    <tr>
      <td>aws.meta-data.placement.availability-zone</td>
      <td>String</td>
      <td>The AWS data centre name the application is in.</td>
    </tr>
    <tr>
      <td>aws.meta-data.placement.region</td>
      <td>String</td>
      <td>The AWS geographic region the application is in.</td>
    </tr>
    <tr>
      <td>aws.meta-data.local-ipv4</td>
      <td>String</td>
      <td>Local LAN (internal) IP address of the host machine</td>
    </tr>
    <tr>
      <td>aws.meta-data.public-ipv4</td>
      <td>String</td>
      <td>External IP address of the host machine. Not applicable for machines within VPCs; not guaranteed to have a value.</td>
    </tr>

  </tbody>
</table>

If *Knobs* is configured to load AWS values, but finds that it is in actual fact not running in AWS (for example in the local dev scenario) it will just ignore these keys and fail to fetch them gracefully (another good reason you should always `lookup` and not `require` for these keys).

<a name="reloading"></a>

# Dynamic Reloading

A `Config` can be reloaded from its resources with the `reload` method. This will load any changes to the underlying files and notify subscribers of those changes.

You can subscribe to notifications of changes to the configuration with the `subscribe` method. For example, to print to the console whenever a configuration changes:

```
cfg.flatMap(_.subscribe {
  case (n, None) => Task { println(s"The parameter $n was removed") }
  case (n, Some(v)) => Task { println(s"The parameter $n has a new value: $v") }
})
```

<a name="best-practice"></a>

# Best Practices

TBD


