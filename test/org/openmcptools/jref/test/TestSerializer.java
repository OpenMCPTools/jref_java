package org.openmcptools.jref.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.*;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmcptools.jref.JRef;

public class TestSerializer {

	static class TestJRef extends JRef {
		
		public TestJRef() {
			super();
		}
		@Override
		protected Object serialize(Object subject, Map<Object, String> pointers, String location,
				String objectnamefield, Function<String, Map<String, Object>> refbuilderfn) {
			return super.serialize(subject, pointers, location, objectnamefield, refbuilderfn);
		}
	}
	
	protected static TestJRef fixture;
	
    @BeforeAll
    static void initAll() {
        // Run once before all tests in this class
    	fixture = new TestJRef();
    }
	
    // Helper to create a Map quickly (similar to Python's {} literal)
    private Map<String, Object> mapOf(Object... args) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            map.put((String) args[i], args[i + 1]);
        }
        return map;
    }

    // Helper to create a List quickly (similar to Python's [] literal)
    private List<Object> listOf(Object... args) {
        return new ArrayList<>(Arrays.asList(args));
    }

    @Test
    public void test_serialize_scalars() {
        /** Test serialization of basic scalar types. **/
        assertEquals(fixture.serialize(null), null);
        assertEquals(fixture.serialize(true), true);
        assertEquals(fixture.serialize(false), false);
        assertEquals(fixture.serialize(123), 123);
        assertEquals(fixture.serialize(1.23), 1.23);
        assertEquals(fixture.serialize("hello world"), "hello world");
    }

    @Test
    public void test_serialize_simple_structures() {
        /** Test serialization of simple lists and dictionaries. **/
        assertEquals(fixture.serialize(listOf(1, 2, 3)), listOf(1, 2, 3));
        assertEquals(fixture.serialize(mapOf("a", 1, "b", "c")), mapOf("a", 1, "b", "c"));
        assertEquals(fixture.serialize(mapOf("list", listOf(1, 2), "val", 3)), mapOf("list", listOf(1, 2), "val", 3));
    }

    @Test
    public void test_serialize_multiple_references() {
        /** Test that multiple references to the same object use JSON pointers. **/
        Map<String, String> inner = new HashMap<>();
        inner.put("key", "value");
        
        Map<String, Object> outer = new HashMap<>();
        outer.put("first", inner);
        outer.put("second", inner);
        
        // The first occurrence is serialized fully, the second as a reference
        Map<String, Object> expected = mapOf(
            "first", mapOf("key", "value"),
            "second", mapOf("$ref", "#/first")
        );
        assertEquals(fixture.serialize(outer), expected);
    }

    @SuppressWarnings("unchecked")
	@Test
    public void test_serialize_circular_references() {
        /** Test serialization of circular object graphs. **/
        Map<String, Object> node_a = new HashMap<>();
        node_a.put("name", "A");
        
        Map<String, Object> node_b = new HashMap<>();
        node_b.put("name", "B");
        node_b.put("parent", node_a);
        
        node_a.put("child", node_b);
        
        // node_a at ""
        // node_a["child"] at "/child" (node_b content)
        // node_b["parent"] should be a ref to node_a at ""
		Map<String, Object> res = (Map<String, Object>) fixture.serialize(node_a);
        assertEquals(res.get("name"), "A");
        assertEquals(((Map<String, Object>) res.get("child")).get("name"), "B");
        assertEquals(((Map<String, Object>) res.get("child")).get("parent"), mapOf("$ref", "#"));
    }

    @Test
    public void test_serialize_list_references() {
        /** Test references within lists. **/
        Map<String, Integer> item = new HashMap<>();
        item.put("id", 1);
        
        List<Object> data = listOf(item, item);
        
        List<Object> expected = listOf(
            mapOf("id", 1),
            mapOf("$ref", "#/0")
        );
        assertEquals(fixture.serialize(data), expected);
    }

    @Test
    public void test_serialize_escaping() {
        /** Test that JSON pointer escaping works for keys with ~ and /. **/
        Map<String, Integer> inner = Map.of("val", 1);
        Map<String, Object> data = new LinkedHashMap<>(); // Use LinkedHashMap to ensure consistent pointer generation
        data.put("a/b", inner);
        data.put("c~d", inner);
        
        // Pointers should be escaped: / becomes ~1, ~ becomes ~0
        @SuppressWarnings("unchecked")
		Map<String, Object> res = (Map<String, Object>) fixture.serialize(data);
        assertEquals(res.get("c~d"), mapOf("$ref", "#/a~1b"));
    }

    // Custom classes for testing
    static class User {
        String name;
        String email;
        User(String name, String email) {
            this.name = name;
            this.email = email;
        }
    }

    @Test
    public void test_serialize_custom_object() {
        /** Test serialization of arbitrary Python objects. **/
        User user = new User("Alice", "alice@example.com");
        Object res = fixture.serialize(user);
        
        // Objects are serialized as their fields (simulating __dict__)
        assertEquals(res, mapOf("name", "Alice", "email", "alice@example.com"));
    }

    static class Item {
        String name;
        Item(String name) {
            this.name = name;
        }
    }

    @Test
    public void test_serialize_custom_object_references() {
        /** Test that repeated custom objects use references. **/
        Item it = new Item("shared");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("one", it);
        data.put("two", it);
        
        @SuppressWarnings("unchecked")
		Map<String, Object> res = (Map<String, Object>) fixture.serialize(data);
        assertEquals(res.get("one"), mapOf("name", "shared"));
        assertEquals(res.get("two"), mapOf("$ref", "#/one"));
    }

    static class Entity {
        String eid;
        Object data;
        Entity(String eid, Object data) {
            this.eid = eid;
            this.data = data;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void test_serialize_objectnamefield() {
        /** Test using a custom field for object identification instead of id(). **/
        Entity e1 = new Entity("id123", "data1");
        Entity e2 = new Entity("id124", e1); 
        
        List<Object> data = listOf(e1, e2);
        
        // In JRef.java, to specify objectnamefield, we use the 5-arg serialize method
        List<Object> res = (List<Object>) fixture.serialize(data, new HashMap<>(), "", "eid", (uri) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("$ref", "#" + uri); // JRef.java uses a private _build_ptr; we replicate logic here
            return map;
        });

        assertEquals(res.get(0), mapOf("eid", "id123", "data", "data1"));
        assertEquals(res.get(1), mapOf("eid", "id124", "data", mapOf("$ref", "#/0")));
    }

    @Test
    public void test_deserialize_basic() {
        /** Test basic deserialization without references. **/
        Map<String, Object> data = mapOf("a", 1, "b", listOf(2, 3));
        Object res = fixture.deserialize(new LinkedHashMap<>(data)); // Pass copy as deserialize might mutate
        assertEquals(res, data);
    }

    @Test
    public void test_deserialize_with_refs() {
        /** Test resolving JSON pointers during deserialization. **/
        Map<String, Object> data = mapOf(
            "shared", mapOf("x", 10),
            "other", mapOf("$ref", "#/shared")
        );
        @SuppressWarnings("unchecked")
		Map<String, Object> res = (Map<String, Object>) fixture.deserialize(data);
        assertEquals(res.get("other"), mapOf("x", 10));
        // Check that it's the exact same object instance
        assertSame(res.get("other"), res.get("shared"));
    }

    @SuppressWarnings("unchecked")
	@Test
    public void test_deserialize_nested_refs() {
        /** Test complex nested references. **/
        Map<String, Object> data = mapOf(
            "users", listOf(
                mapOf("name", "Alice", "id", 1),
                mapOf("name", "Bob", "id", 2)
            ),
            "admin", mapOf("$ref", "#/users/0")
        );
		Map<String, Object> res = (Map<String, Object>) fixture.deserialize(data);
        assertEquals(((Map<String, Object>) res.get("admin")).get("name"), "Alice");
        assertSame(res.get("admin"), ((List<Object>) res.get("users")).get(0));
    }

    @SuppressWarnings("unchecked")
	@Test
    public void test_deserialize_circular() {
        /** Test deserialization of circular references. **/
        Map<String, Object> data = new HashMap<>();
        data.put("child", mapOf("parent", mapOf("$ref", "#")));
        
		Map<String, Object> res = (Map<String, Object>) fixture.deserialize(data);
        assertSame(((Map<String, Object>) res.get("child")).get("parent"), res);
    }

    @Test
    public void test_deserialize_invalid_ref() {
        /** Test that invalid references raise an Exception. **/
        Map<String, Object> data = mapOf(
            "a", 1,
            "b", mapOf("$ref", "#/nonexistent")
        );
        try {
            fixture.deserialize(data);
            fail("Expected RuntimeException was not thrown");
        } catch (RuntimeException e) {
            assertEquals("Invalid reference", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
	@Test
    public void test_deserialize_escaped_refs() {
        /** Test deserialization with escaped pointer segments. **/
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("a/b", mapOf("val", 42));
        data.put("ref", mapOf("$ref", "#/a~1b"));
        
		Map<String, Object> res = (Map<String, Object>) fixture.deserialize(data);
        assertEquals(((Map<String, Object>) res.get("ref")).get("val"), 42);
        assertSame(res.get("ref"), res.get("a/b"));
    }
}
