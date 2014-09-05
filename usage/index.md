---
layout: default
title:  "Usage"
section: "usage"
---

# Getting started

<a name="getting-started"></a>

First up you need to add the dependency for the monitoring library to your `build.scala` or your `build.sbt` file:

````
libraryDependencies += "oncue.svc.knobs" %% "core" % "1.1.+"
````
(check for the latest release by [looking on the nexus](http://nexus.svc.oncue.com/nexus/content/repositories/releases/oncue/svc/knobs/core_2.10/)).

Once you have the dependency added to your project and SBT `update` downloaded JAR, you're ready to start adding configuration knobs to your project!

# Configuration Resources

In the general case, configurations are loaded using the `load` method in the `knobs` package. Configurations are loaded from `Resource`s. The types of resources supported are:

  * `FileResource` - loads a file from the file system
  * `URLResource` - loads any URI supported by the class `java.net.URI`
  * `ClassPathResource` - loads a file from the classpath
  * `SysPropsResource` - loads system properties matching a specific pattern

Resources can be declared `Required` or `Optional`. It's an error to load a `Required` file that doesn't exist. It is not an error to try to load a nonexistent file if that file is marked `Optional`.

For example, to require the file "foo.cfg" from the classpath:

```
import knobs._

val cfg: Task[Config] = load(Required(ClassPathResource("foo.cfg")))
```

The `Task` data type represents an I/O action that loads the file and parses the configuration. You can get the `Config` out of it with `cfg.run`, but this is not the recommended usage pattern. The recommended way of accessing the contents of a `Task` is to use its `map` and `flatMap` methods.


# Dynamic Reloading

A `Config` can be reloaded from its resources with the `reload` method. This will load any changes to the underlying files and notify subscribers of those changes.

You can subscribe to notifications of changes to the configuration with the `subscribe` method. For example, to print to the console whenever a configuration changes:

```
cfg.flatMap(_.subscribe {
  case (n, None) => Task { println(s"The parameter $n was removed") }
  case (n, Some(v)) => Task { println(s"The parameter $n has a new value: $v") }
})
```

