---
layout: default
title:  "Usage"
section: "usage"
---

# Getting started

<a name="getting-started"></a>

First up you need to add the dependency for the monitoring library to your `build.scala` or your `build.sbt` file:

````
libraryDependencies += "oncue.svc.knobs" %% "core" % "x.x.+"
````
(check for the latest release by [looking on the nexus](http://nexus.svc.oncue.com/nexus/content/repositories/releases/oncue/svc/knobs/core_2.10/)).

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
  Required(ClassPathResource("foo.cfg")))
```

This of course assumes that the `foo.cfg` file is located in the root of the classpath (`/`). If you had a file that was not in the root, you could simply do something like:

```
import knobs.{Required,ClassPathResource,Config}
import scalaz.concurrent.Task

val cfg: Task[Config] = knobs.loadImmutable(
  	Required(ClassPathResource("subfolder/foo.cfg")))
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
libraryDependencies += "oncue.svc.knobs" %% "zookeeper" % "x.x.+"
```

This will put the Zookeeper knobs supporting classes into your project classpath. Regardless of which type of connection management you choose (see below), the mechanism for defining the resource is the same:

```

```

### Configuring Zookeeper Knobs with Knobs



### Functional Connection Management

For the functional implementation, you essentially have to build your application within the context of the `Task[A]` that contains the connection to Zookeeper (thus allowing real-time updates to the configuration). If you're dealing with an impure application such as *Play!*, its horrific use of mutable state will basically make this impossible and you'll need to use the imperative alternative. 

```
import knobs._

ZooKeeper.withDefault { r => for {
  cfg <- load(Required(r))

  // Application code here

} yield () }.run

```


### Imperative Connection Management

Sadly, for most systems using any kind of framework, you'll likely have to go with the imperative implementation to knit knobs correctly into the application lifecycle.

```
import knobs._

// somewhere at the edge of the world call this function
// to connect to zookeeper. Connection will then stay open
// up until the point the close task is executed.
val (r, close) = ZooKeeper.unsafeDefault

// Application code here


// then at some time later (whenever, essentially) close 
// the connection to zookeeper when you wish to shut 
// down application.
close.run

```

Where possible, do try and design your applications as `Free[A]` so you can actually use the functional style. The author appreciates that this is unlikely to be the common case from day one. 



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


