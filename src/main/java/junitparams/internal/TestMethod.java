package junitparams.internal;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import javax.lang.model.type.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.model.*;

import junitparams.*;
import junitparams.mappers.*;

/**
 * A wrapper for a test method
 * 
 * @author Pawel Lipinski
 */
public class TestMethod {
    private FrameworkMethod frameworkMethod;
    private Class<?> testClass;
    private Parameters parametersAnnotation;
    private FileParameters fileParametersAnnotation;
    private Object[] params;
    private final String PARAMETER_COMMAND_LINE_OVERRIDE = "parameters";

    public TestMethod(FrameworkMethod method, TestClass testClass) {
        this.frameworkMethod = method;
        this.testClass = testClass.getJavaClass();
        this.parametersAnnotation = frameworkMethod.getAnnotation(Parameters.class);
        this.fileParametersAnnotation = frameworkMethod.getAnnotation(FileParameters.class);

        if (parametersAnnotation != null && fileParametersAnnotation != null) {
            throw new IllegalArgumentException("Both @Parameters and @FileParameters exist on " + frameworkMethod.getName()
                + ". Remove one of them!");
        }
    }

    public String name() {
        return frameworkMethod.getName();
    }

    public static List<TestMethod> listFrom(List<FrameworkMethod> annotatedMethods, TestClass testClass) {
        List<TestMethod> methods = new ArrayList<TestMethod>();

        for (FrameworkMethod frameworkMethod : annotatedMethods)
            methods.add(new TestMethod(frameworkMethod, testClass));

        return methods;
    }

    @Override
    public int hashCode() {
        return frameworkMethod.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TestMethod))
            return false;

        if (!frameworkMethod.getName().equals(((TestMethod) obj).frameworkMethod.getName()))
            return false;

        if (!frameworkMethod.getMethod().getParameterTypes().equals(((TestMethod) obj).frameworkMethod.getMethod().getParameterTypes()))
            return false;

        return true;
    }

    Class<?> testClass() {
        return testClass;
    }

    public boolean isIgnored() {
        if (frameworkMethod.getAnnotation(Ignore.class) != null)
            return true;

        if (isParameterised() && parametersSets().length == 0)
            return true;

        return false;
    }

    public boolean isNotIgnored() {
        return !isIgnored();
    }

    public Annotation[] annotations() {
        return frameworkMethod.getAnnotations();
    }

    Description describe() {
        if (isNotIgnored() && !describeFlat()) {
            Description parametrised = Description.createSuiteDescription(name());
            Object[] params = parametersSets();
            for (int i = 0; i < params.length; i++) {
                Object paramSet = params[i];
                parametrised.addChild(
                    Description.createTestDescription(testClass(), Utils.stringify(paramSet, i) + " (" + name() + ")", annotations()));
            }
            return parametrised;
        } else {
            return Description.createTestDescription(testClass(), name(), annotations());
        }
    }

    private boolean describeFlat() {
        return System.getProperty("JUnitParams.flat") != null;
    }

    public Object[] parametersSets() {
        if (params != null)
            return params;

        //Check command line input first
        if (System.getProperty(PARAMETER_COMMAND_LINE_OVERRIDE) != null){
            params = System.getProperty(PARAMETER_COMMAND_LINE_OVERRIDE).split(";");
        }

        else if (parametersAnnotation != null) {
            params = paramsFromValue();

            if (params.length == 0)
                params = paramsFromSource();

            if (params.length == 0)
                params = paramsFromMethod(testClass());
        }
        else if (fileParametersAnnotation != null) {
            params = paramsFromFile();
        }

        return params;
    }

    private Object[] paramsFromFile() {
        try {
            Reader reader = createProperReader();
            DataMapper mapper = fileParametersAnnotation.mapper().newInstance();
            try {
                return mapper.map(reader);
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Could not successfully read parameters from file: " + fileParametersAnnotation.value(), e);
        }
    }

    private Reader createProperReader() throws IOException {
        String filepath = fileParametersAnnotation.value();

        if (filepath.indexOf(':') < 0)
            return new FileReader(filepath);

        String protocol = filepath.substring(0, filepath.indexOf(':'));
        String filename = filepath.substring(filepath.indexOf(':') + 1);

        if ("classpath".equals(protocol)) {
            return new InputStreamReader(getClass().getClassLoader().getResourceAsStream(filename));
        } else if ("file".equals(protocol)) {
            return new FileReader(filename);
        }

        throw new IllegalArgumentException("Unknown file access protocol. Only 'file' and 'classpath' are supported!");
    }

    private Object[] paramsFromValue() {
        return parametersAnnotation.value();
    }

    private Object[] paramsFromSource() {
        if (sourceClassUndefined())
            return new Object[] {};

        Class<?> sourceClass = parametersAnnotation.source();
        String method = parametersAnnotation.method();

        if (method.isEmpty())
            return fillResultWithAllParamProviderMethods(sourceClass);
        else {
            return paramsFromMethod(sourceClass);
        }
    }

    private Object[] paramsFromMethod(Class<?> classWithMethod) {
        String methodAnnotation = parametersAnnotation.method();

        if (methodAnnotation.isEmpty())
            return invokeMethodWithParams(defaultMethodName(), classWithMethod);

        List<Object> result = new ArrayList<Object>();
        for (String methodName : methodAnnotation.split(",")) {
            for (Object param : invokeMethodWithParams(methodName.trim(), classWithMethod))
                result.add(param);
        }

        return result.toArray();
    }

    private boolean sourceClassUndefined() {
        return parametersAnnotation.source().isAssignableFrom(NullType.class);
    }

    private String defaultMethodName() {
        String methodName;
        methodName = "parametersFor" + frameworkMethod.getName().substring(0, 1).toUpperCase()
            + frameworkMethod.getName().substring(1);
        return methodName;
    }

    private Object[] invokeMethodWithParams(String methodName, Class<?> testClass) {
        Method provideMethod = findParamsProvidingMethodInTestclassHierarchy(methodName, testClass);

        return invokeParamsProvidingMethod(testClass, provideMethod);
    }

    private Method findParamsProvidingMethodInTestclassHierarchy(String methodName, Class<?> testClass) {
        Method provideMethod = null;
        Class<?> declaringClass = testClass;
        while (declaringClass.getSuperclass() != null) {
            try {
                provideMethod = declaringClass.getDeclaredMethod(methodName);
                break;
            } catch (Exception e) {
            }
            declaringClass = declaringClass.getSuperclass();
        }
        if (provideMethod == null)
            throw new RuntimeException("Could not find method: " + methodName + " so no params were used.");
        return provideMethod;
    }

    @SuppressWarnings("unchecked")
    private Object[] invokeParamsProvidingMethod(Class<?> testClass, Method provideMethod) {
        try {
            Object testObject = testClass.newInstance();
            provideMethod.setAccessible(true);
            Object result = provideMethod.invoke(testObject);

            if (Object[].class.isAssignableFrom(result.getClass())) {
                Object[] params = (Object[]) result;
                return encapsulateParamsIntoArrayIfSingleParamsetPassed(params);
            }

            if (Iterable.class.isAssignableFrom(result.getClass())) {
                try {
                    ArrayList<Object[]> res = new ArrayList<Object[]>();
                    for (Object[] paramSet : (Iterable<Object[]>) result)
                        res.add(paramSet);
                    return res.toArray();
                } catch (ClassCastException e1) {
                    // Iterable with consecutive paramsets, each of one param
                    ArrayList<Object> res = new ArrayList<Object>();
                    for (Object param : (Iterable<?>) result)
                        res.add(new Object[] { param });
                    return res.toArray();
                }
            }

            if (Iterator.class.isAssignableFrom(result.getClass())) {
                try {
                    ArrayList<Object[]> res = new ArrayList<Object[]>();
                    Iterator<Object[]> iterator = (Iterator<Object[]>) result;
                    while(iterator.hasNext())
                        res.add(iterator.next());
                    return res.toArray();
                } catch (ClassCastException e1) {
                    // Itertor with consecutive paramsets, each of one param
                    ArrayList<Object> res = new ArrayList<Object>();
                    Iterator<?> iterator = (Iterator<?>) result;
                    while(iterator.hasNext())
                        res.add(new Object[] { iterator.next() });
                    return res.toArray();
                }
            }

            throw new ClassCastException();

        } catch (ClassCastException e) {
            throw new RuntimeException("The return type of: " + provideMethod.getName() + " defined in class " + testClass
                + " is not Object[][] nor Iterable<Object[]>. Fix it!", e);
        } catch (Exception e) {
            throw new RuntimeException("Could not invoke method: " + provideMethod.getName() + " defined in class " + testClass
                + " so no params were used.", e);
        }
    }

    private Object[] fillResultWithAllParamProviderMethods(Class<?> sourceClass) {
        List<Object> result = getParamsFromSourceHierarchy(sourceClass);
        if (result.isEmpty())
            throw new RuntimeException(
                "No methods starting with provide or they return no result in the parameters source class: "
                    + sourceClass.getName());

        return result.toArray(new Object[] {});
    }

    private List<Object> getParamsFromSourceHierarchy(Class<?> sourceClass) {
        List<Object> result = new ArrayList<Object>();
        while (sourceClass.getSuperclass() != null) {
            result.addAll(gatherParamsFromAllMethodsFrom(sourceClass));
            sourceClass = sourceClass.getSuperclass();
        }

        return result;
    }

    private List<Object> gatherParamsFromAllMethodsFrom(Class<?> sourceClass) {
        List<Object> result = new ArrayList<Object>();
        Method[] methods = sourceClass.getDeclaredMethods();
        for (Method prividerMethod : methods) {
            if (prividerMethod.getName().startsWith("provide")) {
                if (!Modifier.isStatic(prividerMethod.getModifiers()))
                    throw new RuntimeException("Parameters source method " +
                        prividerMethod.getName() +
                        " is not declared as static. Change it to a static method.");
                try {
                    result.addAll(Arrays.asList(getDataFromMethod(prividerMethod)));
                } catch (Exception e) {
                    throw new RuntimeException("Cannot invoke parameters source method: " + prividerMethod.getName(), e);
                }
            }
        }
        return result;
    }

    private Object[] getDataFromMethod(Method prividerMethod) throws IllegalAccessException, InvocationTargetException {
        return encapsulateParamsIntoArrayIfSingleParamsetPassed((Object[]) prividerMethod.invoke(null));
    }

    private Object[] encapsulateParamsIntoArrayIfSingleParamsetPassed(Object[] params) {
        if (frameworkMethod.getMethod().getParameterTypes().length != params.length)
            return params;

        if (params.length == 0)
            return params;

        Object param = params[0];
        if (param == null || !param.getClass().isArray())
            return new Object[] { params };

        return params;
    }

    public boolean isParameterised() {
        return frameworkMethod.getMethod().isAnnotationPresent(Parameters.class)
            || frameworkMethod.getMethod().isAnnotationPresent(FileParameters.class);
    }

    void warnIfNoParamsGiven() {
        if (isNotIgnored() && isParameterised() && parametersSets().length == 0)
            System.err.println("Method " + name() + " gets empty list of parameters, so it's being ignored!");
    }

    public FrameworkMethod frameworkMethod() {
        return frameworkMethod;
    }

}