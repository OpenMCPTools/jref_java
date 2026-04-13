# JRef Java (local-only)

JSON Reference (JRef) extends JSON with a reference type. References allow for
circular data to be serialized as JSON or for duplicated data to be serialized
more efficiently. References take the form of `{ "$ref": "#/path/to/target" }`
where the URL fragment is a JSON Pointer locating a position in the document.

JRef can be used to reference locations in external documents as well, but this
implementation only supports references local to the current document. See the
[JRef Specification](https://github.com/hyperjump-io/json-reference/blob/main/spec.md)
for more information on the full specification and
[@hyperjump/browser](https://github.com/hyperjump-io/browser) for a full
implementation supporting external references.

JRef serialization/deserialization has also been implemented in [Typescript](https://github.com/OpenMCPTools/jref_typescript) and [Python](https://github.com/OpenMCPTools/jref_python). Since each JRef implementation is based upon the json ptr RFC 6901 spec, they will interoperate.

## Usage

Complete example app code is [here](test/org/openmcptools/jref/test/TestApp.java)


```java
// Create data
var bob = new Human("Bob", "Marley");
var ziggy = new Human("Ziggy", "Marley", bob);
// Assign child  object to two different fields
var data = Map.of("foo", ziggy, "bar", ziggy, "x", bob, "y", bob);    	
System.out.println("data=" + data);
```
Console Output

```console
data={foo=org.openmcptools.jref.JRef$Human@66048bfd, x=org.openmcptools.jref.JRef$Human@61443d8f, y=org.openmcptools.jref.JRef$Human@61443d8f, bar=org.openmcptools.jref.JRef$Human@66048bfd} 
// Object graph has memory references
```
Serialize

```java
var s = jref.serialize(data);
System.out.println("serialized data="+s);
```

Console Output

```console
serialized data={foo={first=Ziggy, last=Marley, parent={first=Bob, last=Marley, parent=null}}, x={$ref=#/foo/parent}, y={$ref=#/foo/parent}, bar={$ref=#/foo}}
// $refs used to reference Human instances
```

Deserialize

```java
var ds = jref.deserialize(s);
System.out.println("deserialized data=" + ds);
```

Console Ouptut

```console
deserialized data={foo={first=Ziggy, last=Marley, parent={first=Bob, last=Marley, parent=null}}, x={first=Bob, last=Marley, parent=null}, y={first=Bob, last=Marley, parent=null}, bar={first=Ziggy, last=Marley, parent={first=Bob, last=Marley, parent=null}}}

```

## Contributing

Contributions are welcome! Please create an issue to propose and discuss any
changes you'd like to make before implementing it. If it's an obvious bug with
an obvious solution or something simple like a fixing a typo, creating an issue
isn't required. You can just send a PR without creating an issue. 
