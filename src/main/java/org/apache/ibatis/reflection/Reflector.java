/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  /**
   * 对应的类
   */
  private final Class<?> type;
  /**
   * 可读属性数组
   */
  private final String[] readablePropertyNames;
  /**
   * 可写属性集合
   */
  private final String[] writablePropertyNames;
  /**
   * 属性对应的 setting 方法的映射。
   *
   * key 为属性名称
   * value 为 Invoker 对象
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();
  /**
   * 属性对应的 getting 方法的映射。
   *
   * key 为属性名称
   * value 为 Invoker 对象
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();
  /**
   * 属性对应的 setting 方法的方法参数类型的映射。{@link #setMethods}
   *
   * key 为属性名称
   * value 为方法参数类型
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  /**
   * 属性对应的 getting 方法的返回值类型的映射。{@link #getMethods}
   *
   * key 为属性名称
   * value 为返回值的类型
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  /**
   * 默认构造方法
   */
  private Constructor<?> defaultConstructor;
  /**
   * 不区分大小写的属性集合
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
    // 设置对应的类
    type = clazz;
    // <1> 初始化 defaultConstructor
    addDefaultConstructor(clazz);
    // <2> // 初始化 getMethods 和 getTypes ，通过遍历 getting 方法
    addGetMethods(clazz);
    // <3> // 初始化 setMethods 和 setTypes ，通过遍历 setting 方法。
    addSetMethods(clazz);
    // <4> // 初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性。
    addFields(clazz);
    // <5> 初始化 readablePropertyNames、writeablePropertyNames、caseInsensitivePropertyMap 属性
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }
//查找默认无参构造方法
  private void addDefaultConstructor(Class<?> clazz) {
    // 获得所有构造方法
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    // 遍历所有构造方法，查找无参的构造方法
    // 如果构造方法可以访问，赋值给 defaultConstructor
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0)
      .findAny().ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  //初始化 getMethods 和 getTypes ，通过遍历 getting 方法
  private void addGetMethods(Class<?> clazz) {
    // <1> 属性与其 getting 方法的映射。
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // <2> 获得所有方法
    Method[] methods = getClassMethods(clazz);
    // <3> 遍历所有方法
    // <3.1> 参数大于 0 ，说明不是 getting 方法，忽略
    // <3.2> 以 get 和 is 方法名开头，说明是 getting 方法
    // <3.3> 获得属性
    // <3.4> 添加到 conflictingGetters 中
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
    // <4> 解决 getting 冲突方法
    resolveGetterConflicts(conflictingGetters);
  }

  //解决 getting 冲突方法。最终，一个属性，只保留一个对应的方法
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 getting 方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;// 最匹配的方法
      String propName = entry.getKey();
      boolean isAmbiguous = false;
      for (Method candidate : entry.getValue()) {
        // winner 为空，说明 candidate 为最匹配的方法
        if (winner == null) {
          winner = candidate;
          continue;
        }
        // <1> 基于返回类型比较
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        // 类型相同
        if (candidateType.equals(winnerType)) {
          // 返回值了诶选哪个相同，应该在 getClassMethods 方法中，已经合并。所以抛出 ReflectionException 异常
          if (!boolean.class.equals(candidateType)) {
            isAmbiguous = true;
            break;
            // 选择 boolean 类型的 is 方法
          } else if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
          // 不符合选择子类
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // OK getter type is descendant
          // <1.1> 符合选择子类。因为子类可以修改放大返回值。例如，父类的一个方法的返回值为 List ，子类对该方法的返回值可以覆写为 ArrayList 。
        } else if (winnerType.isAssignableFrom(candidateType)) {
          winner = candidate;
          // <1.2> 返回类型冲突
        } else {
          isAmbiguous = true;
          break;
        }
      }
      // <2> 添加到 getMethods 和 getTypes 中
      addGetMethod(propName, winner, isAmbiguous);

      //<1> 处，基于返回类型比较。重点在 <1.1> 和 <1.2> 的情况，因为子类可以修改放大返回值，所以在出现这个情况时，
      // 选择子类的该方法。例如，父类的一个方法的返回值为 List ，子类对该方法的返回值可以覆写为 ArrayList 。代码如下：
      //public class A {
      //
      //    List<String> getXXXX();
      //
      //}
      //
      //public class B extends A {
      //
      //    ArrayList<String> getXXXX(); // 选择它
      //
      //}
    //  <2> 处，调用 #addGetMethod(String name, Method method) 方法，添加方法到 getMethods 和 getTypes 中。代码如下：

    }
  }

  private void addGetMethod(String name, Method method, boolean isAmbiguous) {
    // <2.1> 判断是合理的属性名
    MethodInvoker invoker = isAmbiguous
        ? new AmbiguousMethodInvoker(method, MessageFormat.format(
            "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
            name, method.getDeclaringClass().getName()))
        : new MethodInvoker(method);
    // <2.2> 添加到 getMethods 中 我们可以看到一个 MethodInvoker 类
    getMethods.put(name, invoker);
    // <2.3> 添加到 getTypes 中 我们可以看到一个 TypeParameterResolver 类
    Type returnType = TypeParameterResolver.resolveReturnType(method, type);
    //typeToClass(Type src) 方法，获得 java.lang.reflect.Type 对应的类
    getTypes.put(name, typeToClass(returnType));
  }

  //初始化 setMethods 和 setTypes ，通过遍历 setting 方法
  private void addSetMethods(Class<?> clazz) {
    // 属性与其 setting 方法的映射。
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    // 获得所有方法
    Method[] methods = getClassMethods(clazz);
    // 遍历所有方法
    // <1> 方法名为 set 开头
    // 参数数量为 1
    // 获得属性
    // 添加到 conflictingSetters 中
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
    // <2> 解决 setting 冲突方法
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    if (isValidPropertyName(name)) {
      List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
      list.add(method);
    }
  }
//解决 setting 冲突方法
  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 setting 方法
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      Class<?> getterType = getTypes.get(propName);
      boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
      boolean isSetterAmbiguous = false;
      Method match = null;
      // <1> 遍历属性对应的 setting 方法
      for (Method setter : setters) {
        // 和 getterType 相同，直接使用
        if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (!isSetterAmbiguous) {
          // 选择一个更加匹配的
          match = pickBetterSetter(match, setter, propName);
          isSetterAmbiguous = match == null;
        }
      }
      // <2> 添加到 setMethods 和 setTypes 中
      if (match != null) {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
        MessageFormat.format(
            "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.",
            property, setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
    setMethods.put(property, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
    setTypes.put(property, typeToClass(paramTypes[0]));
    return null;
  }

  //添加到 setMethods 和 setTypes 中
  private void addSetMethod(String name, Method method) {
    MethodInvoker invoker = new MethodInvoker(method);
    // 添加到 setMethods 中
    setMethods.put(name, invoker);
    // 添加到 setTypes 中
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
    setTypes.put(name, typeToClass(paramTypes[0]));
  }
//代码比较简单，就是寻找 Type 真正对应的类。
  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    // 普通类型，直接使用类
    if (src instanceof Class) {
      result = (Class<?>) src;
      // 泛型类型，使用泛型
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
      // 泛型数组，获得具体类
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    // 都不符合，使用 Object 类
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  //初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性。实际上，它是 #addGetMethods(...)
  // 和 #addSetMethods(...) 方法的补充，因为有些 field ，不存在对应的 setting 或 getting 方法，所以直接使用对应的
  // field ，而不是方法
  private void addFields(Class<?> clazz) {
    // 获得所有 field 们
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      // <1> 添加到 setMethods 和 setTypes 中
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        //若 setMethods 不存在，则调用 #addSetField(Field field) 方法，添加到 setMethods 和 setTypes 中
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          addSetField(field);
        }
      }
      // 添加到 getMethods 和 getTypes 中
      if (!getMethods.containsKey(field.getName())) {
        addGetField(field);
      }
    }
    // 递归，处理父类
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  //注意，此处创建的是 SetFieldInvoker 对象
  private void addSetField(Field field) {
    // 判断是合理的属性
    if (isValidPropertyName(field.getName())) {
      // 添加到 setMethods 中
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      // 添加到 setTypes 中
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }
//注意，此处创建的是 GetFieldInvoker 对象。详细解析
  private void addGetField(Field field) {
    // 判断是合理的属性
    if (isValidPropertyName(field.getName())) {
      // 添加到 getMethods 中
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      // 添加到 getMethods 中
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

//获得所有方法
  private Method[] getClassMethods(Class<?> clazz) {
    // 每个方法签名与该方法的映射
    Map<String, Method> uniqueMethods = new HashMap<>();
    // 循环类，类的父类，类的父类的父类，直到父类为 Object
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {
      // <1> 记录当前类定义的方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // <2> 记录接口中定义的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }
      // 获得父类
      currentClass = currentClass.getSuperclass();
    }
    // 转换成 Method 数组返回
    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[0]);
  }
//添加方法数组到 uniqueMethods 中
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) { // 忽略 bridge 方法，参见 https://www.zhihu.com/question/54895701/answer/141623158 文章
        // <3> 获得方法签名
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 当 uniqueMethods 不存在时，进行添加
        if (!uniqueMethods.containsKey(signature)) {
          // 添加到 uniqueMethods 中
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }
//获得方法签名
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    // 返回类型
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    // 方法名
    sb.append(method.getName());
    // 方法参数
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * 判断，是否可以修改可访问性
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  //用于对它的属性进行访问
  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }


  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  /**
   *     * 创建 PropertyTokenizer 对象，对 `name` 进行**分词**。当有子表达式，继续递归调用 `#buildProperty(String name, StringBuilder builder)` 方法，并将结果添加到 `builder` 中；否则，结束，直接添加到 `builder` 中。
   *     * 在两个 `<4>` 处，解决“下划线转驼峰”的关键是，通过 `Reflector.caseInsensitivePropertyMap` 属性，忽略大小写。代码如下：
   * 不区分大小写的属性集合
   */
  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
