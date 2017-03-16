package com.autocognite.pvt.unitee.testobject.lib.loader;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.Logger;

import com.autocognite.arjuna.annotations.Instances;
import com.autocognite.arjuna.annotations.Skip;
import com.autocognite.arjuna.annotations.TestClass;
import com.autocognite.arjuna.interfaces.TestVariables;
import com.autocognite.arjuna.interfaces.Value;
import com.autocognite.arjuna.utils.DataBatteries;
import com.autocognite.pvt.ArjunaInternal;
import com.autocognite.pvt.arjuna.enums.SkipCode;
import com.autocognite.pvt.batteries.config.Batteries;
import com.autocognite.pvt.batteries.console.Console;
import com.autocognite.pvt.batteries.discoverer.DiscoveredFile;
import com.autocognite.pvt.batteries.discoverer.DiscoveredFileAttribute;
import com.autocognite.pvt.batteries.hocon.HoconReader;
import com.autocognite.pvt.batteries.hocon.HoconResourceReader;
import com.autocognite.pvt.unitee.testobject.lib.definitions.JavaTestClassDefinition;
import com.autocognite.pvt.unitee.testobject.lib.definitions.TestDefinitionsDB;
import com.autocognite.pvt.unitee.testobject.lib.java.JavaTestClass;
import com.autocognite.pvt.unitee.testobject.lib.java.TestClassConstructorType;
import com.autocognite.pvt.unitee.testobject.lib.loader.tree.DependencyTreeBuilder;

public class JavaTestClassDefinitionsLoader implements TestDefinitionsLoader {
	private Logger logger = Logger.getLogger(Batteries.getCentralLogName());
	private ClassLoader classLoader = null;
	private DependencyTreeBuilder depTreeBuilder = new DependencyTreeBuilder();
	String testDir = null;
	public static Map<String, Set<String>> CLASS_ANNOTATION_COMPAT = new HashMap<String,Set<String>>();
	public static Map<String, Set<String>> METHOD_ANNOTATION_COMPAT = new HashMap<String,Set<String>>();
	
	public JavaTestClassDefinitionsLoader() throws Exception{
		HoconReader reader1 = new HoconResourceReader(this.getClass().getResourceAsStream("/com/autocognite/pvt/text/class_annotations_compatibility.conf"));
		reader1.process();
		Map<String, Value> rules1 = reader1.getProperties();
		for (String r: rules1.keySet()){
			Set<String> aSet = new HashSet<String>();
			aSet.addAll(rules1.get(r).asStringList());
			CLASS_ANNOTATION_COMPAT.put(r, aSet);
		}
		
		HoconReader reader2 = new HoconResourceReader(this.getClass().getResourceAsStream("/com/autocognite/pvt/text/method_annotations_compatibility.conf"));
		reader2.process();
		Map<String, Value> rules2 = reader2.getProperties();
		for (String r: rules2.keySet()){
			Set<String> aSet = new HashSet<String>();
			aSet.addAll(rules2.get(r).asStringList());
			METHOD_ANNOTATION_COMPAT.put(r, aSet);
		}
	}
	
	@Override
	public void setTestDir(String testDir) throws Exception{
		this.testDir = testDir;	
	}
	
	private ClassLoader getClassLoader(DiscoveredFile f) throws MalformedURLException{
		if (ArjunaInternal.displayUserTestLoadingInfo){
			logger.debug("Get Class Loader for: " + f.getAttribute(DiscoveredFileAttribute.FULL_NAME));
		}
		String path = null;
		if (f.getAttribute(DiscoveredFileAttribute.CONTAINER_TYPE).toLowerCase().equals("jar")){
			path = String.format(
					"%s/%s", f.getAttribute(DiscoveredFileAttribute.DIRECTORY_ABSOLUTE_PATH), 
					f.getAttribute(DiscoveredFileAttribute.CONTAINER));
		} else {
			path =  testDir;
		}
		
		if (ArjunaInternal.displayUserTestLoadingInfo){
			logger.debug("Loading Path: " + path);
		}
		
		URL[] urls = new URL[] {new File(path).toURI().toURL()};
		// Create a new class loader with the directory
		 classLoader = new URLClassLoader(urls); //, this.getClass().getClassLoader());	
		 return classLoader;
	}
	
	
	private ClassLoader getClassLoader(String relPath) throws MalformedURLException{
		String path = this.testDir + relPath;
		
		URL[] urls = new URL[] {new File(path).toURI().toURL()};
		// Create a new class loader with the directory
		 classLoader = new URLClassLoader(urls); //, this.getClass().getClassLoader());	
		 return classLoader;
	}
	
	@Override
	public void load(DiscoveredFile f){
		JavaTestClass jTestClass = null;
		String qualifiedName = getQualifiedName(f);
		if (ArjunaInternal.displayUserTestLoadingInfo){
			logger.debug("Get Class Loader for: " + qualifiedName);
		}

		try {
			//boolean include = JavaObjectFilter.shouldIncludeClass(cls);
//							logger.debug("Filtering: " + fullQualifiedClassName);
			Class<?> klass = this.loadClass(f, qualifiedName);
			
			Annotation[] annos = klass.getAnnotations();
			ArrayList<String> annNames = new ArrayList<String>();

			for(Annotation a : annos){
				if (a.annotationType().getName().startsWith("com.autocognite.arjuna")){
					annNames.add(a.annotationType().getSimpleName());
				}
			}
			
			// Check for compatible annotations usage.
			// Only applicable if more than one annotations exist for one method
			if (annNames.size() > 1){
				Collections.sort(annNames);
				String mainAnn = annNames.get(0);
				annNames.remove(0);
				if (!JavaTestClassDefinitionsLoader.CLASS_ANNOTATION_COMPAT.containsKey(mainAnn)){
					Console.displayError(String.format("There is a critical error with your test class: %s", qualifiedName));
					Console.displayError(String.format("Arjuna found that it is annotated with @%s.", mainAnn));
					Console.displayError(String.format("Along with this you have annotated the class with: %s.", annNames.toString()));
					Console.displayError(String.format("@%s annotation can not be used along with any other annotation.", mainAnn));
					Console.displayError(String.format("Please correct the annotation usage."));
					Console.displayError("Exiting...");
					System.exit(1);					
				} else {
					List<String> incompatibles = new ArrayList<String>();
					boolean annAnomaly = false;
					for (String annName: annNames){
						if (!JavaTestClassDefinitionsLoader.CLASS_ANNOTATION_COMPAT.get(mainAnn).contains(annName)){
							incompatibles.add(annName);
							annAnomaly = true;							
						} 
					}
					
					if (annAnomaly){
						Console.displayError(String.format("There is a critical error with your test class: %s", qualifiedName));
						Console.displayError(String.format("Arjuna found that it is annotated with @%s.", mainAnn));						
						Console.displayError(String.format("Along with this you have annotated the class with: %s.", annNames.toString()));
						Console.displayError(String.format("Out of these, these annotations are incompatanle with @%s: %s.", mainAnn, incompatibles.toString()));
						Console.displayError(String.format("Please correct the annotation usage."));
						Console.displayError("Exiting...");
						System.exit(1);		
					}
				}
			}

			if (!this.isTestClass(klass)) {
				ArjunaInternal.processNonTestClass(klass);
				TestDefinitionsDB.addNonTestClassName(klass.getName());
				return;				
			}
			
			JavaTestClassDefinition classDef = new JavaTestClassDefinition();
			classDef.setUserTestClass(klass);
			
			// Filter early for better performance, no further processing
			if (ArjunaInternal.displayLoadingInfo){
				logger.debug("Process selection: " + qualifiedName);
			}
			processSkip(klass.getName(), klass, classDef);
			
			int creatorThreadCount = JavaTestLoadingUtils.getCreatorThreadCount(klass);
			if (creatorThreadCount < 1){
				System.err.println(String.format("Method Thread count must be >=1. Correction needed for: %s", classDef.getQualifiedName()));
				System.err.println("Exiting...");
				System.exit(1);
			}
			classDef.setCreatorThreadCount(creatorThreadCount);
			
			// For Data ref processing
			classDef.setDataRefPresent(JavaTestLoadingUtils.isDataRefPresent(klass));
			if (classDef.isDataRefPresent()){
				String dataRefName = JavaTestLoadingUtils.getDataRefName(klass);
				String filePath = JavaTestLoadingUtils.getDataRefPath(klass);
				if (dataRefName.equals("NOT_SET")){
					dataRefName = FilenameUtils.getBaseName(filePath).toUpperCase();
				}
				if (ArjunaInternal.displayDataMethodProcessingInfo){
					logger.debug(String.format("Now registering data reference with name %s.", dataRefName));
				}
				classDef.addFileDataRefWithPath(dataRefName, filePath);
			}

			boolean instancesAnnPresent = JavaTestLoadingUtils.isInstancesAnnotationPresent(klass);
			Instances instancesAnn = null;
			boolean userHasSuppliedProperties = false;
			HashMap<Integer,HashMap<String,String>> instanceProps = new HashMap<Integer,HashMap<String,String>>();
			int instanceCount = 1;
			int instanceThreadCount = 1;
			if (instancesAnnPresent){
				if (ArjunaInternal.displayInstanceProcessingInfo){
					logger.debug("Found @Instances Annotation");
				}
				instancesAnn = (Instances) klass.getAnnotation(Instances.class);
				instanceCount = JavaTestLoadingUtils.getInstancesCount(instancesAnn);
				if (instanceCount == -1){
					System.err.println(String.format("Instance count must be >=1. Correction needed for: %s", classDef.getQualifiedName()));
					System.err.println("Exiting...");
					System.exit(1);
				}
				
				instanceThreadCount = JavaTestLoadingUtils.getInstanceThreadCount(instancesAnn);
				if (instanceThreadCount == -1){
					System.err.println(String.format("Instance Thread count must be >=1. Correction needed for: %s", classDef.getQualifiedName()));
					System.err.println("Exiting...");
					System.exit(1);
				}
				userHasSuppliedProperties = JavaTestLoadingUtils.hasUserSuppliedProperties(qualifiedName, instancesAnn);
			}
			
			classDef.setInstanceCount(instanceCount);
			classDef.setInstanceThreadCount(instanceThreadCount);
			
			instanceProps = JavaTestLoadingUtils.loadUDVFromInstancesAnnotation(instancesAnn, classDef.getInstanceCount(), userHasSuppliedProperties);

			ConstructorDef constructorType = new ConstructorDef();
			Constructor<?> constructor = this.getConstructor(klass, constructorType, userHasSuppliedProperties, classDef.isDataRefPresent());
			
			classDef.setConstructor(constructor);
			classDef.setConstructorType(constructorType.type);
			
			if (ArjunaInternal.displayLoadingInfo){
				logger.debug("Adding instances. count=" + classDef.getInstanceCount());
			}
//			logger.debug(classDef.getInstanceCount());
			for (int i=1; i <= classDef.getInstanceCount(); i++){
				classDef.setUdvForInstance(i, instanceProps.get(i));
			}
			
			TestCreatorLoader creatorLoader = new JavaTestMethodsDefinitionLoader(this, classDef);
			creatorLoader.loadDefinitions();
			
			TestDefinitionsDB.registerTestClassDefinition(klass.getName(), classDef);
		} catch (Throwable e) {
			e.printStackTrace();
		}
		
	}
	
	private String getQualifiedName(DiscoveredFile f){
		String fullQualifiedClassName = null;
		if (f.getAttribute(DiscoveredFileAttribute.PACKAGE_DOT_NOTATION).equals("")){
			fullQualifiedClassName = f.getAttribute(DiscoveredFileAttribute.NAME);
		} else {
			fullQualifiedClassName = f.getAttribute(DiscoveredFileAttribute.PACKAGE_DOT_NOTATION)
					+ "."
					+ f.getAttribute(DiscoveredFileAttribute.NAME);				
		}
		return fullQualifiedClassName;
	}
	
//	private Class<?> loadClass(String fullName) throws Exception{
//		return classLoader.loadClass(fullName);
//	}

	private Class<?> loadClass(DiscoveredFile f, String fullName) throws Exception{
		try {
			if (ArjunaInternal.displayUserTestLoadingInfo){
				logger.debug("Try default loading");
			}
			return this.getClassLoader(f).loadClass(fullName);
		} catch (Throwable e){
			if (f.getAttribute(DiscoveredFileAttribute.CONTAINER_TYPE).toLowerCase().equals("jar")){
				throw e;
			}
//			logger.debug(fullName);
			List<String> parts = DataBatteries.split(fullName, "\\.");
			int i = 0;
			Class<?> klass = null;
			while (i < parts.size()){
				List<String> temp = parts.subList(i, parts.size());
				List<String> temp2 = null;
				if (i == 0){
					temp2 = new ArrayList<String>();
				} else {
					temp2 = parts.subList(0, i);
				}
//				logger.debug(temp);
				if (temp.size() == 1){
					klass = getClassLoader("/" + DataBatteries.join(temp2, "/")).loadClass(temp.get(0));
//					logger.debug(klass);
					return klass;
				}
				String pkg = DataBatteries.join(temp, ".");
//				logger.debug(pkg);
				
				try{
					klass = getClassLoader("/" + DataBatteries.join(temp2, "/")).loadClass(pkg);
//					logger.debug(klass);
					return klass;
				} catch (Throwable h){
//					logger.debug(e.getMessage());
//					logger.debug(e.getMessage());
				}
				
				i++;
			}
		}
		
		throw new Exception("Not able to load test class: " + fullName);
}
	private boolean isTestClass(Class<?> klass){
		return klass.isAnnotationPresent(TestClass.class);
	}
	
	private void processSkip(String fullName, Class<?> klass, JavaTestClassDefinition classDef) throws Exception{
		if (klass.isAnnotationPresent(Skip.class)){
			classDef.setSkipped(SkipCode.SKIPPED_CLASS_ANNOTATION);
		}
	}
	
	public Constructor<?> getConstructor(Class<?> klass, ConstructorDef constructorType, boolean userHasSuppliedProperties, boolean userHasSuppliedDataRef) throws Exception{
		Constructor<?> constructor = null;
		try{
			constructor =  klass.getConstructor(TestVariables.class);
			constructorType.type = TestClassConstructorType.SINGLEARG_TESTVARS;
		} catch (NoSuchMethodException e) {
			try{
				constructor =  klass.getConstructor();
				constructorType.type = TestClassConstructorType.NO_ARG;
			} catch (NoSuchMethodException f) {
				System.err.println(String.format("You must define either of the following public constructors for %s: ",klass.getSimpleName()));
				System.err.println(String.format("Option 1: public %s(TestVariables testClassVars)",klass.getSimpleName()));
				System.err.println(String.format("Option 2: public %s()",klass.getSimpleName()));
				System.err.println("Exiting...");
				System.exit(1);							
			}							
		}
		
		return constructor;
			
	}

	@Override
	public void validateDependencies() throws Exception {
		TestDefinitionsDB.validateDependencies();
	}
	
}

class ConstructorDef{
	 public TestClassConstructorType type = TestClassConstructorType.NO_ARG;
}
