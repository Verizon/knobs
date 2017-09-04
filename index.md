---
layout: default
title:  "Home"
section: "home"
---

# Overview

<a name="overview"></a>

Knobs is a configuration library for Scala. It is based on the [Data.Configurator](https://github.com/bos/configurator/) library for Haskell, but is extended in a number of ways to make it more useful.

Features include:

  * Loading configurations from the file system, URIs, the classpath, and system properties.
  * A simple but flexible configuration language, supporting  several of the most commonly needed types of data, along with interpolation of strings from the configuration, environment variables (e.g. `$(HOME)`), or Java system properties (e.g. `$(path.separator)`).
  * Subscription-based notification of changes to configuration properties.
  * An `import` directive allows the configuration of a complex application to be split across several smaller files, or to be shared across several applications.
  * Helpful error messages when config files have errors in them.
  * An extensible configuration loader. Extensions exist for loading config values from AWS, Typesafe Config (HOCON), and ZooKeeper.
  * Automatic reloading of the configuration when its resources change. Currently supported by both Filesystem and Zookeeper resources. Your extensions can make use of this feature to automatically reload your custom resources as well.

<a name="syntax"></a>

# Syntax

A configuration file consists of a series of directives and comments, encoded in UTF-8. A comment begins with a `#` character, and continues to the end of a line.

Files and directives are processed from first to last, top to bottom.

### Binding a name to a value ###

A binding associates a name with a value.

```
my_string = "hi mom!"
your-int-33 = 33
his_bool = on
HerList = [1, "foo", off]
```

A name must begin with a Unicode letter, which is followed by zero or more Unicode alphanumeric characters, hyphens `-`, or underscores `_`.

Bindings are created or overwritten in the order which they are encountered. It is legitimate to bind a name multiple times, in which the last value wins.

```
a = 1
a = true
# value of a is now true, not 1
```

### Value types ###

The configuration file format supports the following data types:

  * Booleans, represented as `on` or `off`, `true` or `false`. These are case sensitive, so do not try to use `True` instead of `true`!
  * Signed integers, represented in base 10.
  * Double precision floating point numbers in scientific notation, e.g. `0.0012` or `1.2e-3`.
  * Unicode strings, represented as text (possibly containing escape sequences) surrounded by double quotes.
  * Durations, represented as a double precision floating point number or integer followed by a time unit specification, e.g. `3 minutes` or `1h`.
  * Heterogeneous lists of values, represented as an opening square bracket `[`, followed by a series of comma-separated values, ending with a closing square bracket `]`.

The following escape sequences are recognized in a text string:

  * `\b` backspace
  * `\f` formfeed
  * `\n` newline
  * `\r` carriage return
  * `\t` tab
  * `\"` double quote
  * `\\` backslash

A time unit specification for a Duration can be any of the following:

  * Days: `d`, `day`, or `days`
  * Hours: `h`, `hour`, or `hours`
  * Minutes: `min`, `mins`, `minute`, or `minutes`
  * Seconds: `s`, `sec`, `secs`, `second`, or `seconds`
  * Milliseconds: `ms`, `milli`, `millis`, `millisecond`, or `milliseconds`
  * Microseconds: `μs`, `micro`, `micros`, `microsecond`, or `microseconds`
  * Nanoseconds: `ns`, `nano`, `nanos`, `nanosecond` or `nanoseconds`

### String interpolation ###

Strings support interpolation, so that you can dynamically construct a string based on data in your configuration, the OS environment, or system properties.

If a string value contains the special sequence `$(foo)` (for any name `foo`), then Knobs will look up the name `foo` in the configuration data and substitute its value. If it can't find that name, it will look in the Java system properties. Failing that, Knobs will look in the OS environment for a matching environment variable.

It is an error for a string interpolation fragment to contain a name that cannot be found either in the current configuration or the system environment.

To represent a single literal `$` character in a string, use a double `$$`.

### Grouping directives ###

It is possible to group a number of directives together under a single prefix:

```
my-group
{
  a = 1

  # groups support nesting
  nested {
    b = "yay!"
  }
}
```

The name of a group is used as a prefix for the items in the group. For instance, the value of `a` above can be retrieved using `lookup("my-group.a")`, and `b` with `lookup("my-group.nested.b")`.

### Importing files ###

To import the contents of another configuration file, use the `import` directive.

```
import "$(HOME)/etc/myapp.cfg"
```

Absolute paths are imported as is. Relative paths are resolved with respect to the file they are imported from. It is an error for an `import` directive to name a file that doesn't exist, cannot be read, or contains errors. Only environment variables, not system properties or other bindings, are interpolated into import directives.

#### File lookup semantics

The meaning of the `import` directive depends on the `Resource` type the configuration is being loaded from. In general, paths are resolved relative to the current file. *Knobs only supports importing files of the same resource type as the current file*. For example a classpath resource can only import other files from the classpath, and an on-disk file resource can only import other files from disk.

* For a `FileResource`, an `import` is resolved as if the working directory were the directory containing the current file.
* For a `ClassPathResource` an `import` resolves to another resource on _the same classloader's classpath_, as either an absolute path or relative to the current file.
* For a `URLResource`, an `import` is resolved using [the semantics of `java.net.URI.resolve`](https://docs.oracle.com/javase/8/docs/api/java/net/URI.html#resolve-java.net.URI-).
* For a `ZooKeeper` resource, a relative `import` resolves to a ZNode relative to the node containing the current file. An absolute path is rooted at the root ZNode available to the ZooKeeper connection.

#### Importing into groups

If an `import` appears inside a group, the group's naming prefix will be applied to all of the names imported from the given configuration file.

Supposing we have a file named "foo.cfg" that looks like this:

```
bar = 1
```

And another file that imports into a group:

```
hi {
  import "foo.cfg"
}
```

This will result in a value named `hi.bar`.
