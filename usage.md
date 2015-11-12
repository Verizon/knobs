---
layout: default
title:  "Usage"
section: "usage"
---

# Getting started

<a name="getting-started"></a>

First you need to add the Bintray resolvers for [scalaz-stream](https://github.com/scalaz/scalaz-stream) and Knobs, 
besides the dependency, to your `build.scala` or `build.sbt` file:

```
resolvers += Resolver.bintrayRepo("scalaz", "releases")

resolvers += Resolver.bintrayRepo("oncue", "releases")

libraryDependencies += "oncue.knobs" %% "core" % "x.x.x"
```

Where `x.x.x` is the desired Knobs version. (Check for the latest release [on Bintray](https://bintray.com/oncue/releases/knobs/view).)

Once you have the resolvers and dependency added to your project and SBT `update` has downloaded the JAR, you're ready to start adding configuration knobs to your project!

<a name="resources"></a>

# Configuration Resources

In the general case, configurations are loaded using the `load` and `loadImmutable` methods in the `knobs` package. Configurations are loaded from one or more `Resource`s. A `Resource` is an abstract concept to model arbitrary locations from which a source of configuration bindings can be loaded. The following `Resource` implementations are currently available:

  * `FileResource` - loads a file from the file system.
  * `URLResource` - loads any URI supported by the class `java.net.URI`.
  * `ClassPathResource` - loads a file from the classpath.
  * `SysPropsResource` - loads system properties matching a specific pattern.
  * `Zookeeper`* - loads the specified zookeeper znode tree (including children from the specified location).

`*` requires the "zookeeper knobs" dependency in addition to knobs core.

Resources can be declared `Required` or `Optional`. Attempting to load a file that does not exist after having declared it `Required` will result in an exception. It is not an error to try to load a nonexistent file if that file is marked `Optional`.

Calling the `loadImmutable` method to load your resources will result in a `Task[Config]`. This is not yet a `Config`, but a `scalaz.concurrent.Task` that can get you a `Config` when you `run` it. See the [Scalaz documentation for the exact semantics](http://docs.typelevel.org/api/scalaz/nightly/index.html#scalaz.concurrent.Task).

The `Task[Config]` is a pure value which, when run, loads your resources and assembles the configuration from them. You can force this to happen and get the `Config` out of it by calling its `run` method, but this is not the recommended usage pattern. The recommended way of accessing the result of a `Task` is to use its `map` and `flatMap` methods (more on this in the specific resource usage and best practices later in this document).

## Classpath Resources

To require the file "foo.cfg" from the classpath:

```scala
scala> import knobs.{Required,ClassPathResource,Config}
import knobs.{Required, ClassPathResource, Config}

scala> import scalaz.concurrent.Task
import scalaz.concurrent.Task

scala> val cfg: Task[Config] = knobs.loadImmutable(
     |   Required(ClassPathResource("foo.cfg")) :: Nil)
cfg: scalaz.concurrent.Task[knobs.Config] = scalaz.concurrent.Task@5815f77a
```

This of course assumes that the `foo.cfg` file is located in the root of the classpath (`/`). If you had a file that was not in the root, you could simply do something like:

```scala
scala> import knobs.{Required,ClassPathResource,Config}
import knobs.{Required, ClassPathResource, Config}

scala> import scalaz.concurrent.Task
import scalaz.concurrent.Task

scala> val cfg: Task[Config] = knobs.loadImmutable(
     |   	Required(ClassPathResource("subfolder/foo.cfg")) :: Nil)
cfg: scalaz.concurrent.Task[knobs.Config] = scalaz.concurrent.Task@6aac7643
```

Classpath resources are immutable and aren't intended to be reloaded in the general case. You can technically reload them, but this has no effect unless you're using a custom ClassLoader or employing some classpath tricks. Usually the classpath resource will exist inside your application JAR at deployment time and won't change at runtime.

## File Resources

`File` resources are probably the most common type of resource you might want to interact with. Here's a simple example of loading an immutable configuration from a file:

```scala
scala> import java.io.File
import java.io.File

scala> import knobs.{Required,FileResource,Config}
import knobs.{Required, FileResource, Config}

scala> import scalaz.concurrent.Task
import scalaz.concurrent.Task

scala> val cfg: Task[Config] = knobs.loadImmutable(
     |   	Required(FileResource(new File("/path/to/foo.cfg"))) :: Nil)
cfg: scalaz.concurrent.Task[knobs.Config] = scalaz.concurrent.Task@dadfa91
```

On-disk files can be reloaded. [See below](#reloading) for information about reloading configurations.

## System Property Resources

Although you usually wouldn't want to load your entire configuration from Java system properties, there may be occasions when you want to use them to set specific configuration values (perhaps to override a few bindings). Here's an example:

```scala
scala> import knobs.{Required,SysPropsResource,Config,Prefix}
import knobs.{Required, SysPropsResource, Config, Prefix}

scala> import scalaz.concurrent.Task
import scalaz.concurrent.Task

scala> val cfg: Task[Config] = knobs.loadImmutable(
     |   	Required(SysPropsResource(Prefix("oncue"))) :: Nil)
cfg: scalaz.concurrent.Task[knobs.Config] = scalaz.concurrent.Task@1d820e8c
```

System properties are just key/value pairs, and *Knobs* provides a couple of different `Pattern`s that you can use to match on the key name:

* `Exact`: the exact name of the system property you want to load. Useful when you want one specific key.
* `Prefix`: In the case where you want to load multiple system properties, you can do so using a prefix; knobs will then go and load all the system properties whose names start with that string.

## Zookeeper

The Zookeeper support can be used in two different styles. Depending on your application design, you can choose the implementation that best suits your specific style and requirements. Either way, make sure to add the following dependency to your project:

```
libraryDependencies += "oncue.knobs" %% "zookeeper" % "x.x.+"
```

Where `x.x` is the desired Knobs version.

Regardless of which type of connection management you choose (see below), the mechanism for defining the resource is the same:

```
import knobs._

// `r` is provided by the connection management options below
load(Required(r) :: Nil)
```

### Configuring Zookeeper Knobs with Knobs

The Zookeeper module of Knobs is itself configured using Knobs (high fives all around). This is simply to provide a location for the Zookeeper cluster. There is a default shipped inside the Knobs Zookeeper JAR, so if you do nothing, the system, will use the following (default) configuration:

```
zookeeper {
  connection-string = "localhost:2181"
  path-to-config = "/knobs.cfg"
}

```

Typically you will want to override this at deployment time.

* Bind `connection-string` to the actual location of your Zookeeper cluster.
* Bind `path-to-config` to the ZNode path, inside of your Zookeeper, that points to your Knobs configuration file. Note that if this file `import`s other files, it must reference them using a path relative to its own path.

### Functional Connection Management

For the functional implementation, you essentially have to build your application within the context of the `scalaz.concurrent.Task` that contains the connection to Zookeeper (thus allowing you to subscribe to updates to your configuration from Zookeeper in real time). If you're dealing with an impure application such as *Play!*, its horrific use of mutable state will make this more difficult and you'll probably want to use the imperative alternative (see the next section). Otherwise, the usage pattern is the traditional monadic style:

```
import knobs._

ZooKeeper.withDefault { r => for {
  cfg <- load(Required(r) :: Nil)

  // Application code here

} yield () }.run

```


### Imperative Connection Management

If you're not building your application with monadic composition, you'll sadly have to go with an imperative style to knit Knobs correctly into the application lifecycle:

```
import knobs._

// somewhere at the outer layers of your application,
// call this function to connect to zookeeper.
// The connection will stay open until you run the `close` task.
val (r, close) = ZooKeeper.unsafeDefault

// This then loads the configuration from the ZooKeeper resource:
val cfg = load(Required(r) :: Nil)

// Your application code goes here

// Close the connection to ZooKeeper before shutting down
// your application:
close.run
```

Where possible, we recommend designing your applications as a [free monad](http://timperrett.com/2013/11/21/free-monads-part-1/) or use a [reader monad transformer](http://docs.typelevel.org/api/scalaz/nightly/index.html#scalaz.Kleisli) like `Kleisli[Task,Config,A]` to "inject" your configuration to where it's needed. Of course, this is not a choice available to everyone. If your hands are tied with an imperative framework, you can pass Knobs configurations in the same way that you normally do.

<a name="reading"></a>

## Resource combinators

A few combinators are available on `Resource`s:

* `r1 or r2` is a composite `Resource` which, when loaded, attempts to load `r1`, and then attempts to load `r2` if `r1` fails.
* `r.required` is alternate syntax for `Required(r)`. It marks the resource `r` as required for the configuration.
* `r.optional` is alternate syntax for `Optional(r)`. It marks the resource `r` as optional to the configuration.

# Reading Values

Once loaded, configurations come in two flavors: `Config` and `MutableConfig`. These are loaded using the `loadImmutable` and `load` methods, respectively, in the `knobs` package.

## Immutable Configurations

Once you have a `Config` instance loaded, and you want to lookup some values from it, the API is very simple. Here's an example:

```scala
scala> import knobs._
import knobs._

scala> // load some configuration
     | val config: Task[Config] = loadImmutable(
     |   Required(FileResource(new File("someFile.cfg")) or
     |   ClassPathResource("someName.cfg")) :: Nil
     | )
config: scalaz.concurrent.Task[knobs.Config] = scalaz.concurrent.Task@3b2ec70b

scala> case class Connection(usr: String, pwd: String, port:Option[Int])
defined class Connection

scala> // do something with it
     | val connection: Task[Connection] =
     |   for {
     |     cfg <- config
     |     usr = cfg.require[String]("db.username")
     |     pwd = cfg.require[String]("db.password")
     |     port = cfg.lookup[Int]("db.port")
     |   } yield Connection(usr, pwd, port)
connection: scalaz.concurrent.Task[Connection] = scalaz.concurrent.Task@73a26f9f
```

There are two different ways of looking up a configuration value in this example:

* `require[A](k)` attempts to look up the value bound to the key `k` and convert it into the specified type `A` - a `String` in this example. It will throw an exception if the key `k` is not bound to a value or is bound to a value that cannot be converted to the type `A`.

* `lookup[A](k)` has the same conversion semantics as `require[A](k)` with the addition that the function returns an `Option[A]`. If the key `k` is not bound to a value, or the value could not properly be converted into the specified type `A`, then `lookup` returns `None`.

Typically you will want to use `lookup` more than you use `require`, but there are of course valid use cases for `require`, such as in this example--if this were a database application and the connection to the database was not properly configured, the whole application is broken anyway so we might as well throw an exception.

In addition to these lookup functions, `Config` has two other useful methods:

* `++`: allows you to add two configuration objects together; this can be useful if you're loading multiple configurations from different sources. You can think of this as having the same semantics as concatenating the configuration files from which the the two objects were loaded.

* `subconfig`: given a `Config` instance you can get a new `Config` instance with only keys that satisfy a given predicate. This is really useful if, for example, you only wanted to collect keys under the "foo" group: `cfg.subconfig("foo")`.

## Mutable Configurations

Alternatively, you can call `load` to get a `MutableConfig`. A `MutableConfig` can be turned into an immutable `Config` by calling its `immutable` method.

`MutableConfig` also comes with a number of methods that allow you to mutate the configuration at runtime (all in the `Task` monad, of course).

It also allows you to dynamically `reload` it from its resources, which will pick up any changes that have been made to those resources and notify subscribers. See the next section.

<a name="reloading"></a>

# Dynamic Reloading

A `MutableConfig` can be reloaded from its resources with the `reload` method. This will load any changes to the underlying files for any subsequent lookups. It will also notify subscribers of those changes.

Additionally, both on-disk and ZooKeeper files support _automatic_ reloading of a `MutableConfig` when the source files change at runtime.

You can subscribe to notifications of changes to the configuration with the `subscribe` method. For example, to print to the console whenever a configuration changes:

```scala
scala> val cfg: Task[MutableConfig] = load(Required(FileResource(new File("someFile.cfg"))) :: Nil)
cfg: scalaz.concurrent.Task[knobs.MutableConfig] = scalaz.concurrent.Task@5e6f7ab2

scala> cfg.flatMap(_.subscribe (Pattern("somePrefix.*"), {
     |   case (n, None) => Task { println(s"The parameter $n was removed") }
     |   case (n, Some(v)) => Task { println(s"The parameter $n has a new value: $v") }
     | }))
res2: scalaz.concurrent.Task[Unit] = scalaz.concurrent.Task@c5a7830
```

You can also get a stream of changes with `changes(p)` where `p` is some `Pattern` (either a `prefix` or an `exact` pattern). This gives you a [scalaz-stream](http://github.com/scalaz/scalaz-stream) `Process[Task, (Name, Option[CfgValue])]` of configuration bindings that match the pattern.

<a name="aws"></a>

# AWS Configuration

If you're running *Knobs* from within an application that is hosted on AWS, you're in luck! *Knobs* comes with automatic support for learning about its surrounding environment and can provide a range of useful configuration settings. For example:

```scala
scala> val c1: Task[Config] =
     |   loadImmutable(Required(FileResource(new File("someFile"))) :: Nil)
c1: scalaz.concurrent.Task[knobs.Config] = scalaz.concurrent.Task@6a720b0a

scala> val cfg = for {
     |   a <- c1
     |   b <- aws.config
     | } yield a ++ b
cfg: scalaz.concurrent.Task[knobs.Config] = scalaz.concurrent.Task@2c5bd592
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
      <td>Dynamically embed Knobs configuration format strings in the AWS instance user-data and Knobs will extract that and graft it to the running Config.</td>
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

If *Knobs* is configured to load AWS values, but finds that it is in actual fact not running in AWS (for example in the local dev scenario), it will just ignore these keys and your `Config` will not contain them (a good reason you should always `lookup` and not `require` these keys).

<a name="best-practice"></a>

# Best Practices

TBD


