package org.openmcptools.jref.test;

import java.util.Map;

import org.openmcptools.jref.JRef;

public class TestApp {

	static class Human {
		String first;
		String last;
		Human parent;

		public Human(String firstName, String lastName, Human parent) {
			this.first = firstName;
			this.last = lastName;
			this.parent = parent;
		}

		public Human(String f, String l) {
			this(f, l, null);
		}
	}

	public static void main(String[] args) {
		JRef jref = new JRef();
		// Create parent
		var bob = new Human("Bob", "Marley");
		var ziggy = new Human("Ziggy", "Marley", bob);
		// Assign child object to two different fields
		var data = Map.of("foo", ziggy, "bar", ziggy, "x", bob, "y", bob);
		System.out.println("data=" + data);
		var s = jref.serialize(data);
		System.out.println("serialized data=" + s);
		var ds = jref.deserialize(s);
		System.out.println("deserialized data=" + ds);
	}

}
