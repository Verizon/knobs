Knobs
=====

Knobs is a configuration library for Scala. It is based on the [Data.Configurator](https://github.com/bos/configurator/) library for Haskell.

Features include:

  * Loading configurations from the file system, URIs, the class path, or system properties.
  * A simple but flexible configuration language, supporting  several of the most commonly needed types of data, along with interpolation of strings from the configuration, environment variables (e.g. `$(HOME)`), or Java system properties (e.g. `$(path.separator)`).
  * Subscription-based notification of changes to configuration properties.
  * An `import` directive allows the configuration of a complex application to be split across several smaller files, or to be shared across several applications.

## Configuration file format ###

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
  * Integers, represented in base 10.
  * Unicode strings, represented as text (possibly containing escape sequences) surrounded by double quotes.
  * Heterogeneous lists of values, represented as an opening square bracket `[`, followed by a series of comma-separated values, ending with a closing square bracket `]`.

The following escape sequences are recognized in a text string:

  * `\b` backspace
  * `\f` formfeed
  * `\n` newline
  * `\r` carriage return
  * `\t` tab
  * `\"` double quote
  * `\\` backslash

### String interpolation ###

String support interpolation, so that you can dynamically construct a string based on data in your configuration, the OS environment, or system properties.

If a string value contains the special sequence `$(foo)` (for any name `foo`), then the name `foo` will be looked up in the configuration data and its value substituted. If that name cannot be found, it will be looked up in the Java system properties. Failing that, Knobs will look in the OS environment for a matching environment variable.

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

Absolute paths are imported as is. Relative paths are resolved with respect to the file they are imported from. It is an error for an `import` directive to name a file that doesn't exist, cannot be read, or contains errors.

If an `import` appears inside a group, the group's naming prefix will be applied to all of the names imported from the given configuration file.

Supposing we have a file named "foo.cfg":

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

## Loading configurations ##

In the general case, configurations are loaded using the `load` method in the `knobs` package. Configurations are loaded from `Resource`s. The types of resources supported are:

  * `FileResource` - loads a file from the file system
  * `URIResource` - loads any URI supported by the class `java.net.URI`
  * `ClassPathResource` - loads a file from the classpath
  * `SysPropsResource` - loads system properties matching a specific pattern

Resources can be declared `Required` or `Optional`. It's an error to load a `Required` file that doesn't exist. It is not an error to try to load a nonexistent file if that file is marked `Optional`.

For example, to require the file "foo.cfg" from the classpath:

```
import knobs._

val cfg: Task[Config] = load(Required(ClassPathResource("foo.cfg")))
```

The `Task` data type represents an I/O action that loads the file and parses the configuration. You can get the `Config` out of it with `cfg.run`, but this is not the recommended usage pattern. The recommended way of accessing the contents of a `Task` is to use its `map` and `flatMap` methods.

A `Config` can be reloaded from its resources with the `reload` method. This will load any changes to the underlying files and notify subscribers of those changes.

You can subscribe to notifications of changes to the configuration with the `subscribe` method. For example, to print to the console whenever a configuration changes:

```
cfg.flatMap(_.subscribe {
  case (n, None) => Task { println(s"The parameter $n was removed") }
  case (n, Some(v)) => Task { println(s"The parameter $n has a new value: $v") }
})
```

