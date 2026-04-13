package org.openmcptools.jref;

import java.util.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.function.Function;

/**
 * JRef/Json Pointer serialization. See serialize and deserialize functions below
 * 
 * @copyright: Jason Desrosiers <jdesrosi@gmail.com> and Scott Lewis <scottslewis@gmail.com>
 */
public class JRef {

    // Type aliases / Constants
    private static final String nil = "";
    private static final String _REF_KEY = "$ref";

    /**
     * Splits a JSON Pointer string into its individual segments.
     * 
     * @param pointer The JSON Pointer string.
     * @return An Iterable of unescaped segments.
     */
    public static Iterable<String> pointerSegments(String pointer) {
        if (pointer.length() > 0 && !pointer.startsWith("/")) {
            throw new IllegalArgumentException("Invalid JSON Pointer");
        }

        List<String> segments = new ArrayList<>();
        int segmentStart = 1;
        int segmentEnd;

        while (segmentStart <= pointer.length()) {
            int position = pointer.indexOf("/", segmentStart);
            segmentEnd = (position == -1) ? pointer.length() : position;
            String segment = pointer.substring(segmentStart, segmentEnd);
            segmentStart = segmentEnd + 1;

            segments.add(unescape(segment));
            
            // If the pointer ended with a '/', we need to add an empty segment for the trailing slash
            if (position != -1 && segmentStart > pointer.length()) {
                segments.add("");
            }
        }
        
        return segments;
    }

    /**
     * Retrieves a value from a JSON structure using a pointer.
     * If subject is null, returns a Function (Getter) that takes a subject.
     */
    public static Object get(String pointer, Object subject) {
        if (subject == null) {
            final List<String> segments = new ArrayList<>();
            pointerSegments(pointer).forEach(segments::add);
            return (Function<Object, Object>) (Object s) -> _get(segments, s);
        } else {
            return _get(pointerSegments(pointer), subject);
        }
    }

    private static Object _get(Iterable<String> segments, Object subject) {
        String cursor = nil;
        for (String segment : segments) {
            subject = applySegment(subject, segment, cursor);
            cursor = append(segment, cursor);
        }
        return subject;
    }

    public static String append(Object segment, String pointer) {
        return pointer + "/" + escape(String.valueOf(segment));
    }

    public static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    public static String unescape(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    public static Object computeSegment(Object value, String segment) {
        if (value instanceof List) {
            return "-".equals(segment) ? ((List<?>) value).size() : Integer.parseInt(segment);
        } else {
            return segment;
        }
    }

    public static Object applySegment(Object value, Object segment, String cursor) {
        if (value == null) {
            throw new RuntimeException(String.format("Value at '%s' is %s and does not have property '%s'", 
                cursor, (cursor.isEmpty() ? "null" : "undefined"), segment));
        } else if (isScalar(value)) {
            String valueType = value.getClass().getSimpleName().toLowerCase();
            throw new RuntimeException(String.format("Value at '%s' is a %s and does not have property '%s'", 
                cursor, valueType, segment));
        } else {
            Object computedSegment = computeSegment(value, String.valueOf(segment));
            if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                if (map.containsKey(computedSegment)) {
                    return map.get(computedSegment);
                }
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                if (computedSegment instanceof Integer) {
                    int index = (Integer) computedSegment;
                    if (index >= 0 && index < list.size()) {
                        return list.get(index);
                    }
                }
            }
            return null;
        }
    }

    /**
     * Check if a value is a scalar (not an object or array).
     */
    public static boolean isScalar(Object value) {
        return value == null || !(value instanceof Map || value instanceof List);
    }

    private static String encode_uri(String uri) {
        try {
        	return new URI(uri).toASCIIString().replace("+", "%20");
        } catch (Exception e) {
        	throw new RuntimeException(e);
        }
    }

    private static String decode_uri(String uri) {
        try {
            return URLDecoder.decode(uri, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return uri;
        }
    }

    private static Map<String, Object> _build_ptr(String uri) {
        Map<String, Object> map = new HashMap<>();
        map.put(_REF_KEY, "#" + encode_uri(uri));
        return map;
    }

    /**
     * serialize java object graph to dict representation
     * 
     * During serialization, if the same object is referred to
     * multiple times, the first serialized copy will have
     * the object contents, and all subsequent references will
     * use jref/json pointer to refer to the first serialized instance
     */
    public static Object serialize(Object subject) {
        return serialize(subject, new HashMap<>(), "", "name", JRef::_build_ptr);
    }

    public static Object serialize(Object subject, 
                                   Map<Object, String> pointers, 
                                   String location, 
                                   String objectnamefield,
                                   Function<String, Map<String, Object>> refbuilderfn) {

        if (pointers == null) {
            pointers = new HashMap<>();
        }

        // Handle boolean, float, int, str
        if (subject instanceof Boolean) {
            return subject;
        } else if (subject instanceof Number) {
            return subject;
        } else if (subject instanceof String) {
            return subject;
        } else if (subject == null) {
            return null;
        }
        // Handle lists
        else if (subject instanceof List) {
            // Store location for this list
            // Use identity hash code to simulate Python's id() for generic Objects, 
            // but for Map/List we should track the instance.
            pointers.put(System.identityHashCode(subject), location);
            
            List<Object> result = new ArrayList<>();
            List<?> subjectList = (List<?>) subject;
            
            for (int i = 0; i < subjectList.size(); i++) {
                Object value = subjectList.get(i);
                int valueId = System.identityHashCode(value);
                
                if ((value instanceof List || value instanceof Map) && pointers.containsKey(valueId)) {
                    result.add(refbuilderfn.apply(pointers.get(valueId)));
                } else {
                    result.add(serialize(value, pointers, append(String.valueOf(i), location), objectnamefield, refbuilderfn));
                }
            }
            return result;
        }
        // Maps
        else if (subject instanceof Map) {
            pointers.put(System.identityHashCode(subject), location);
            
            Map<String, Object> result = new LinkedHashMap<>();
            Map<?, ?> subjectMap = (Map<?, ?>) subject;
            
            for (Map.Entry<?, ?> entry : subjectMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                int valueId = System.identityHashCode(value);
                
                if ((value instanceof List || value instanceof Map) && pointers.containsKey(valueId)) {
                    result.put(key, refbuilderfn.apply(pointers.get(valueId)));
                } else {
                    result.put(key, serialize(value, pointers, append(key, location), objectnamefield, refbuilderfn));
                }
            }
            return result;
        }
        // Handle java objects (POJOs)
        else {
            Object obj_id;
            try {
                // We first try to get it's name property if it has one (via reflection)
                Field field = subject.getClass().getDeclaredField(objectnamefield);
                field.setAccessible(true);
                obj_id = field.get(subject);
            } catch (Exception e) {
                // If it does not have a name then we get an object id
                obj_id = System.identityHashCode(subject);
            }

            if (pointers.containsKey(obj_id)) {
                return refbuilderfn.apply(pointers.get(obj_id));
            } else {
                pointers.put(obj_id, location);
                // Convert POJO to Map to simulate __dict__
                return serialize(getObjectAsMap(subject), pointers, location, objectnamefield, refbuilderfn);
            }
        }
    }

    /**
     * Helper to convert POJO fields to a Map, simulating Python's __dict__
     */
    private static Map<String, Object> getObjectAsMap(Object obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        Class<?> curr = obj.getClass();
        while (curr != null && curr != Object.class) {
            for (Field field : curr.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    map.put(field.getName(), field.get(obj));
                } catch (IllegalAccessException ignored) {}
            }
            curr = curr.getSuperclass();
        }
        return map;
    }

    public static Object deserialize(Object subject) {
        return deserialize(subject, null, "");
    }

    @SuppressWarnings("unchecked")
    public static Object deserialize(Object subject, Object root, String location) {
        if (subject == null || subject instanceof Boolean || subject instanceof Number || subject instanceof String) {
            return subject;
        }
        
        if (root == null) {
            root = subject;
        }

        if (subject instanceof List) {
            List<Object> list = (List<Object>) subject;
            for (int i = 0; i < list.size(); i++) {
                list.set(i, deserialize(list.get(i), root, append(String.valueOf(i), location)));
            }
            return list;
        }

        if (subject instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) subject;
            Object ref = map.get(_REF_KEY);
            if (ref instanceof String) {
                String refStr = (String) ref;
                String[] parts = refStr.split("#", 2);
                if (parts.length > 1) {
                    String fragment = parts[1];
                    String pointer = decode_uri(fragment);
                    Object refValue = get(pointer, root);
                    if (refValue == null) {
                        throw new RuntimeException("Invalid reference");
                    }
                    return refValue;
                }
            }
            
            // If not a reference, recurse through keys
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                map.put(entry.getKey(), deserialize(entry.getValue(), root, append(entry.getKey(), location)));
            }
            return map;
        }

        // Handle generic objects (Reflection)
        // Note: Python code: subject[key] = deserialize(...) implies subject is dict-like
        // or has __setitem__. In Java POJOs, we update fields.
        Class<?> curr = subject.getClass();
        while (curr != null && curr != Object.class) {
            for (Field field : curr.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    Object value = field.get(subject);
                    field.set(subject, deserialize(value, root, append(field.getName(), location)));
                } catch (IllegalAccessException ignored) {}
            }
            curr = curr.getSuperclass();
        }

        return subject;
    }
}

