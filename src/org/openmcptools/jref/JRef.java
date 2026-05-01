package org.openmcptools.jref;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The JRef class is for building and resolving JSON references to replace
 * object references in complex object graphs (e.g. trees). An IETF-standardized
 * <a href="https://datatracker.ietf.org/doc/html/rfc6901">JSON pointers (RFC
 * 6901)</a> syntax is used for serialization interoperability.
 * <p>
 * </p>
 * Using references rather than copies on JSON serialization can decrease the
 * size necessary for transmission over the network and so reduce bandwidth
 * consumption for such data structures.
 * 
 * The {@link #buildRefs(Object)} method takes a java object graph as input and
 * returns an equivalent object graph with all but the <b>first</b> reference to
 * a given object replaced with a reference object with a String with json
 * pointer syntax to the original objecth.
 * 
 * The {@link #resolveRefs(Object)} method resolves all references after the
 * first one.
 * 
 * @copyright: Jason Desrosiers <jdesrosi@gmail.com> and Scott Lewis
 *             <scottslewis@gmail.com>
 */
public class JRef {

	// Type aliases / Constants
	private static final String _REF_KEY = "$ref";

	public JRef() {
	}

	/**
	 * Create Refs in Java object graph to dict representation
	 * 
	 */
	public Object buildRefs(Object subject) {
		return buildRefs(subject, new HashMap<>(), "", "name", JRef::_build_ptr);
	}

	protected Object buildRefs(Object subject, Map<Object, String> pointers, String location, String objectnamefield,
			Function<String, Map<String, Object>> refbuilderfn) {

		if (pointers == null) {
			pointers = new HashMap<>();
		}
		// Handle base types Boolean, float, int, str
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
					result.add(buildRefs(value, pointers, append(String.valueOf(i), location), objectnamefield,
							refbuilderfn));
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
					result.put(key, buildRefs(value, pointers, append(key, location), objectnamefield, refbuilderfn));
				}
			}
			return result;
		}
		// Handle java objects (POJOs)
		else {
			Object obj_id = null;
			try {
				obj_id = getAccessibleFieldValue(subject, objectnamefield);
			} catch (Exception e) {
				// If it does not have a name then we get an object id
				obj_id = System.identityHashCode(subject);
			}

			if (pointers.containsKey(obj_id)) {
				return refbuilderfn.apply(pointers.get(obj_id));
			} else {
				pointers.put(obj_id, location);
				// Convert POJO to Map to simulate __dict__
				return buildRefs(getObjectAsMap(subject), pointers, location, objectnamefield, refbuilderfn);
			}
		}
	}

	public Object resolveRefs(Object subject) {
		return resolveRefs(subject, null, "");
	}

	@SuppressWarnings("unchecked")
	protected Object resolveRefs(Object subject, Object root, String location) {
		if (subject == null || subject instanceof Boolean || subject instanceof Number || subject instanceof String) {
			return subject;
		}

		if (root == null) {
			root = subject;
		}

		if (subject instanceof List) {
			List<Object> list = (List<Object>) subject;
			for (int i = 0; i < list.size(); i++) {
				list.set(i, resolveRefs(list.get(i), root, append(String.valueOf(i), location)));
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
				map.put(entry.getKey(), resolveRefs(entry.getValue(), root, append(entry.getKey(), location)));
			}
			return map;
		}

		// Handle generic objects (Reflection)
		Class<?> curr = subject.getClass();
		while (curr != null && curr != Object.class) {
			for (Field field : curr.getDeclaredFields()) {
				field.setAccessible(true);
				try {
					Object value = field.get(subject);
					field.set(subject, resolveRefs(value, root, append(field.getName(), location)));
				} catch (IllegalAccessException e) {
					throw new RuntimeException("Error setting field=" + field.getName() + " on subject=" + subject, e);
				}
			}
			curr = curr.getSuperclass();
		}

		return subject;
	}

	/////////////////////////// Support methods //////////////////////
	/**
	 * Splits a JSON Pointer string into its individual segments.
	 * 
	 * @param pointer The JSON Pointer string.
	 * @return An Iterable of unescaped segments.
	 */
	protected Iterable<String> pointerSegments(String pointer) {
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

			// If the pointer ended with a '/', we need to add an empty segment for the
			// trailing slash
			if (position != -1 && segmentStart > pointer.length()) {
				segments.add("");
			}
		}

		return segments;
	}

	/**
	 * Retrieves a value from a JSON structure using a pointer. If subject is null,
	 * returns a Function (Getter) that takes a subject.
	 */
	protected Object get(String pointer, Object subject) {
		if (subject == null) {
			final List<String> segments = new ArrayList<>();
			pointerSegments(pointer).forEach(segments::add);
			return (Function<Object, Object>) (Object s) -> _get(segments, s);
		} else {
			return _get(pointerSegments(pointer), subject);
		}
	}

	protected Object _get(Iterable<String> segments, Object subject) {
		String cursor = "";
		for (String segment : segments) {
			subject = applySegment(subject, segment, cursor);
			cursor = append(segment, cursor);
		}
		return subject;
	}

	protected String append(Object segment, String pointer) {
		return pointer + "/" + escape(String.valueOf(segment));
	}

	protected String escape(String segment) {
		return segment.replace("~", "~0").replace("/", "~1");
	}

	protected String unescape(String segment) {
		return segment.replace("~1", "/").replace("~0", "~");
	}

	protected Object computeSegment(Object value, String segment) {
		if (value instanceof List) {
			return "-".equals(segment) ? ((List<?>) value).size() : Integer.parseInt(segment);
		} else {
			return segment;
		}
	}

	protected Object applySegment(Object value, Object segment, String cursor) {
		if (value == null) {
			throw new RuntimeException(String.format("Value at '%s' is %s and does not have property '%s'", cursor,
					(cursor.isEmpty() ? "null" : "undefined"), segment));
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
			return getAccessibleFieldValue(value, String.valueOf(computedSegment));
		}
	}

	protected Field getAccessibleField(Class<?> clazz, String fieldName) {
		try {
			Field f = clazz.getDeclaredField(fieldName);
			f.setAccessible(true);
			return f;
		} catch (NoSuchFieldException | SecurityException e) {
			throw new RuntimeException(String.format("Could not find field on class=%s with name=%s", clazz, fieldName),
					e);
		}
	}

	protected Object getAccessibleFieldValue(Object value, String fieldName) {
		try {
			return getAccessibleField(value.getClass(), fieldName).get(value);
		} catch (IllegalArgumentException | IllegalAccessException e) {
			throw new RuntimeException(
					String.format("Could not get value for field=%s on object=%s with field", fieldName, value));
		}
	}

	/**
	 * Check if a value is a scalar (not an object or array).
	 */
	protected boolean isScalar(Object value) {
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
				} catch (IllegalAccessException e) {
					throw new RuntimeException("Field=" + field.getName() + " cannot be set", e);
				}
			}
			curr = curr.getSuperclass();
		}
		return map;
	}

}
