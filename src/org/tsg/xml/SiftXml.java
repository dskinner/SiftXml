/**
 * Copyright (c) 2012, The Smyth Group
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package org.tsg.xml;

import java.io.IOException;
import java.io.StringReader;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 
 * @author Daniel Skinner <daniel@dasa.cc>
 *
 */
public class SiftXml {
	
	/**
	 * Parse xml.
	 * @param cls Can be given as Model.class or Model[].class
	 * @param source String of xml
	 * @return
	 */
	public static <T extends Object> T parse(Class cls, String source) {
		final SAXParserFactory spf = SAXParserFactory.newInstance();
		
		SAXParser sp;
		XMLReader reader = null;
		try {
			sp = spf.newSAXParser();
			reader = sp.getXMLReader();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		} catch (SAXException e) {
			e.printStackTrace();
		}
		
		return (T) parse(cls, source, reader);
	}
	
	/**
	 * Parse xml with provided XMLReader such as TagSoup for malformed html parsing.
	 * @param cls Can be given as Model.class or Model[].class
	 * @param source String of xml
	 * @param reader XMLReader instance for use during parsing.
	 * @return
	 */
	public static <T extends Object> T parse(Class cls, String source, XMLReader reader) {
		
		XmlHandler xmlHandler = new XmlHandler();
		xmlHandler.setClass(cls);
		reader.setContentHandler(xmlHandler);
		
		try {
			reader.parse(new InputSource(new StringReader(source)));
		} catch (IOException e) {
			e.printStackTrace();
		} catch (SAXException e) {
			e.printStackTrace();
		}
		
		return (T) xmlHandler.mResult;
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE, ElementType.FIELD})
	public @interface XmlElement {
		String name();
		boolean anyChild() default false;
		String xmlns() default NULL;
		
		public static final String NULL = "NULL - DO NOT USE";
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE, ElementType.FIELD})
	public @interface XmlAttribute {
		String name();
	}
	
	public static class XmlHandler extends DefaultHandler {
		StringBuilder mBuilder;
		
		//Object mObject;
		
		LinkedList<List<Object>> mResults;
		Object mResult; // Could be array depending on setClass(...)
		
		Map<Object, Integer> mObjectIndex; // used to count child depth of current object
		LinkedList<Object> mObjectQueue;
		
		LinkedList<Map<String, Field>> mFieldHintsQueue;
		
		Class mClass;
		Class mClassArray;
		LinkedList<String> mClassHintQueue;
		
		public Object getResult() {
			return mResult;
		}
		
		public void setClass(Class cls) {
			if (cls.isArray()) {
				mClassArray = cls;
				mClass = cls.getComponentType();
			} else {
				mClass = cls;
			}
		}
		
		private boolean isAnnotated(Class cls) {
			if (cls.isArray()) cls = cls.getComponentType();
			XmlElement annotation = (XmlElement) cls.getAnnotation(XmlElement.class);
			if (annotation != null) return true;
			
			return false;
		}
		
		private boolean allowAnyChild(Field field) {
			if (field == null) return false;
			
			XmlElement ann = (XmlElement) field.getAnnotation(XmlElement.class);
			if (ann != null) return ann.anyChild();
			
			return false;
		}
		
		private String getClassHint(Class cls) {
			String classHint = cls.getName();
			XmlElement annotation = (XmlElement) cls.getAnnotation(XmlElement.class);
			if (annotation != null) classHint = annotation.name();
			return classHint;
		}
		
		private Map<String, Field> getFieldHints(Class cls) {
			Map<String, Field> fieldHints = new HashMap<String, Field>();
			
			Field[] fields = cls.getFields();
			for (Field field : fields) {
				String fieldHint = field.getName();
				XmlElement annotation = (XmlElement) field.getAnnotation(XmlElement.class);
				if (annotation != null) fieldHint = annotation.name();
				fieldHints.put(fieldHint, field);
			}
			
			return fieldHints;
		}
		
		private Map<String, Field> getAttrHints(Class cls) {
			Map<String, Field> attrHints = new HashMap<String, Field>();
			
			Field[] fields = cls.getFields();
			for (Field field : fields) {
				XmlAttribute annotation = (XmlAttribute) field.getAnnotation(XmlAttribute.class);
				if (annotation != null) attrHints.put(annotation.name(), field);
			}
			
			return attrHints;
		}
		
		private void setObjectAttributes(Object object, Attributes attributes) {
			if (attributes == null) return;
			
			Map<String, Field> attrHints = getAttrHints(object.getClass());
			for (int i=0; i<attributes.getLength(); ++i) {
				String key = attributes.getLocalName(i);
				if (key.length() == 0) key = attributes.getQName(i);
				
				if (attrHints.containsKey(key)) {
					Field field = attrHints.get(key);
					try {
						field.set(object, attributes.getValue(i));
					} catch (Exception e) { e.printStackTrace(); }
				}
			}
		}
		
		private Object getNewInstance(Class cls, Attributes attrs) {
			Object object = null;
			
			try {
				
				if (cls.isArray()) cls = cls.getComponentType();
				
				object = cls.newInstance();
				
				setObjectAttributes(object, attrs);
				mObjectQueue.add(0, object);
				mObjectIndex.put(object, 0);
				mClassHintQueue.add(0, getClassHint(cls));
				mFieldHintsQueue.add(0, getFieldHints(cls));
				
			} catch (Exception e) { e.printStackTrace(); }
			
			return object;
		}
		
		private void setField(Field field, Object object, Object value) {
			try {
				
				Class fieldCls = field.getType();
				
				if (fieldCls.isArray()) {
					Object arr = field.get(object);
					int len = 1;
					
					if (arr == null) {
						arr = Array.newInstance(fieldCls.getComponentType(), len);
					} else {
						len = Array.getLength(arr);
						Object tmp = Array.newInstance(fieldCls.getComponentType(), len+1);
						System.arraycopy(arr, 0, tmp, 0, len);
						arr = tmp;
						len++;
					}
					
					Array.set(arr, len-1, value);
					value = arr;
				}
				
				field.set(object, value);
				
			} catch (Exception e) { e.printStackTrace(); }
		}
		
		@Override
		public void startDocument() throws SAXException {
			mBuilder = new StringBuilder();
			mResults = new LinkedList<List<Object>>();
			mObjectQueue = new LinkedList<Object>();
			mObjectIndex = new HashMap<Object, Integer>();
			mFieldHintsQueue = new LinkedList<Map<String, Field>>();
			mClassHintQueue = new LinkedList<String>();
			
			mResults.add(new ArrayList<Object>());
		}
		
		@Override
		public void endDocument() throws SAXException {
			List<Object> objects = mResults.poll();
			
			if (mClassArray != null) {
				mResult = Array.newInstance(mClass, objects.size());
				for (int i=0; i<objects.size(); ++i) {
					Array.set(mResult, i, objects.get(i));
				}
			} else if (objects.size() != 0){
				mResult = objects.get(0);
			}
		}
		
		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			mBuilder.append(ch, start, length);
		}
		
		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			// the name to grab differs:
			// use qName for Android 2.3, Desktop
			// use localName for Android2.1, 2.3
			String name = localName;
			if (name.length() == 0) name = qName;
			
			Object object = mObjectQueue.peek();
			
			if (mClassHintQueue.size() == 0 && getClassHint(mClass).equals(name)) {
				object = getNewInstance(mClass, attributes);
				mResults.peek().add(object);
				mBuilder = new StringBuilder(); // TODO cheap trick to resolve string builder accumulating data before first element
			} else if (object != null) {
				mObjectIndex.put(object, mObjectIndex.get(object)+1); // update child depth count
				Map<String, Field> fieldHints = mFieldHintsQueue.peek();
				
				if (fieldHints.containsKey(name)) {
					Field field = fieldHints.get(name);
					Class fieldType = field.getType();
					
					// determine if this is an Object member of Root as such members require an XmlElement annotation
					if (isAnnotated(fieldType)) getNewInstance(fieldType, attributes);
				}
				
			}
		}
		
		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			// the name to grab differs:
			// use qName for Android 2.3, Desktop
			// use localName for Android2.1, 2.3
			String name = localName;
			if (name.length() == 0) name = qName;
			
			Object object = mObjectQueue.peek();
			String classHint = mClassHintQueue.peek();
			
			if (name.equals(classHint)) {
				mObjectQueue.poll();
				mClassHintQueue.poll();
				mFieldHintsQueue.poll();
				
				// check if this is nested object and append to parent
				if (mObjectQueue.size() != 0) {
					Map<String, Field> fieldHints = mFieldHintsQueue.peek();
					if (fieldHints.containsKey(classHint)) {
						Field field = fieldHints.get(classHint);
						setField(field, mObjectQueue.peek(), object);
					}
				}
			} else if (object != null) {
				Map<String, Field> fieldHints = mFieldHintsQueue.peek();
				Field field = fieldHints.get(name);
				
				if (field != null && (mObjectIndex.get(object) == 1 || allowAnyChild(field))) {
					Class fieldType = field.getType();
					
					if (!isAnnotated(fieldType)) {
						if (fieldType.isArray()) fieldType = fieldType.getComponentType();
						try {
							Constructor ct = fieldType.getConstructor(new Class[] {String.class});
							Object value = ct.newInstance(new Object[] {mBuilder.toString().trim()});
							setField(field, object, value);
						} catch (Exception e) { e.printStackTrace(); }
					}
				}
				
				mBuilder = new StringBuilder();
				mObjectIndex.put(object, mObjectIndex.get(object)-1);
			}
		}
	}
}
