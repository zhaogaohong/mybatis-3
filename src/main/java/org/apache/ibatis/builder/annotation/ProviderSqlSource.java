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
package org.apache.ibatis.builder.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * 实现 SqlSource 接口，基于方法上的 @ProviderXXX 注解的 SqlSource 实现类
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

  private final Configuration configuration;
  /**
   * `@ProviderXXX` 注解的对应的类
   */
  private final Class<?> providerType;
  private final LanguageDriver languageDriver;
  private final Method mapperMethod;
  /**
   * `@ProviderXXX` 注解的对应的方法
   */
  private final Method providerMethod;
  /**
   * `@ProviderXXX` 注解的对应的方法的参数名数组
   */
  private final String[] providerMethodArgumentNames;
  /**
   * `@ProviderXXX` 注解的对应的方法的参数类型数组
   */
  private final Class<?>[] providerMethodParameterTypes;
  /**
   * 若 {@link #providerMethodParameterTypes} 参数有 ProviderContext 类型的，创建 ProviderContext 对象
   */
  private final ProviderContext providerContext;
  /**
   * {@link #providerMethodParameterTypes} 参数中，ProviderContext 类型的参数，在数组中的位置
   */
  private final Integer providerContextIndex;

  /**
   * @deprecated Since 3.5.3, Please use the {@link #ProviderSqlSource(Configuration, Annotation, Class, Method)} instead of this.
   * This constructor will remove at a future version.
   */
  @Deprecated
  public ProviderSqlSource(Configuration configuration, Object provider) {
    this(configuration, provider, null, null);
  }

  /**
   * @since 3.4.5
   * @deprecated Since 3.5.3, Please use the {@link #ProviderSqlSource(Configuration, Annotation, Class, Method)} instead of this.
   * This constructor will remove at a future version.
   */
  @Deprecated
  public ProviderSqlSource(Configuration configuration, Object provider, Class<?> mapperType, Method mapperMethod) {
    this(configuration, (Annotation) provider , mapperType, mapperMethod);
  }

  /**
   * @since 3.5.3
   */
  public ProviderSqlSource(Configuration configuration, Annotation provider, Class<?> mapperType, Method mapperMethod) {
    String candidateProviderMethodName;
    Method candidateProviderMethod = null;
    try {
      this.configuration = configuration;
      this.mapperMethod = mapperMethod;
      Lang lang = mapperMethod == null ? null : mapperMethod.getAnnotation(Lang.class);
      this.languageDriver = configuration.getLanguageDriver(lang == null ? null : lang.value());
      // 获得 @ProviderXXX 注解的对应的类
      this.providerType = getProviderType(provider, mapperMethod);
      // 获得 @ProviderXXX 注解的对应的方法相关的信息
      candidateProviderMethodName = (String) provider.annotationType().getMethod("method").invoke(provider);

      if (candidateProviderMethodName.length() == 0 && ProviderMethodResolver.class.isAssignableFrom(this.providerType)) {
        candidateProviderMethod = ((ProviderMethodResolver) this.providerType.getDeclaredConstructor().newInstance())
            .resolveMethod(new ProviderContext(mapperType, mapperMethod, configuration.getDatabaseId()));
      }
      if (candidateProviderMethod == null) {
        candidateProviderMethodName = candidateProviderMethodName.length() == 0 ? "provideSql" : candidateProviderMethodName;
        for (Method m : this.providerType.getMethods()) {
          if (candidateProviderMethodName.equals(m.getName()) && CharSequence.class.isAssignableFrom(m.getReturnType())) {
            if (candidateProviderMethod != null) {
              throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                  + candidateProviderMethodName + "' is found multiple in SqlProvider '" + this.providerType.getName()
                  + "'. Sql provider method can not overload.");
            }
            candidateProviderMethod = m;
          }
        }
      }
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
    }
    if (candidateProviderMethod == null) {
      throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
          + candidateProviderMethodName + "' not found in SqlProvider '" + this.providerType.getName() + "'.");
    }
    this.providerMethod = candidateProviderMethod;
    this.providerMethodArgumentNames = new ParamNameResolver(configuration, this.providerMethod).getNames();
    this.providerMethodParameterTypes = this.providerMethod.getParameterTypes();

    ProviderContext candidateProviderContext = null;
    Integer candidateProviderContextIndex = null;
    // 初始化 providerContext 和 providerContextIndex 属性
    for (int i = 0; i < this.providerMethodParameterTypes.length; i++) {
      Class<?> parameterType = this.providerMethodParameterTypes[i];
      if (parameterType == ProviderContext.class) {
        if (candidateProviderContext != null) {
          throw new BuilderException("Error creating SqlSource for SqlProvider. ProviderContext found multiple in SqlProvider method ("
              + this.providerType.getName() + "." + providerMethod.getName()
              + "). ProviderContext can not define multiple in SqlProvider method argument.");
        }
        candidateProviderContext = new ProviderContext(mapperType, mapperMethod, configuration.getDatabaseId());
        candidateProviderContextIndex = i;
      }
    }
    this.providerContext = candidateProviderContext;
    this.providerContextIndex = candidateProviderContextIndex;
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    // <1> 创建 SqlSource 对象
    SqlSource sqlSource = createSqlSource(parameterObject);
    // <2> 获得 BoundSql 对象
    return sqlSource.getBoundSql(parameterObject);
  }

  private SqlSource createSqlSource(Object parameterObject) {
    try {
      String sql;
      if (parameterObject instanceof Map) {
        // <1> 获得 SQL
        int bindParameterCount = providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
        if (bindParameterCount == 1 &&
          (providerMethodParameterTypes[Integer.valueOf(0).equals(providerContextIndex) ? 1 : 0].isAssignableFrom(parameterObject.getClass()))) {
          sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
        } else {
          @SuppressWarnings("unchecked")
          Map<String, Object> params = (Map<String, Object>) parameterObject;
          sql = invokeProviderMethod(extractProviderMethodArguments(params, providerMethodArgumentNames));
        }
      } else if (providerMethodParameterTypes.length == 0) {
        sql = invokeProviderMethod();
      } else if (providerMethodParameterTypes.length == 1) {
        if (providerContext == null) {
          sql = invokeProviderMethod(parameterObject);
        } else {
          sql = invokeProviderMethod(providerContext);
        }
      } else if (providerMethodParameterTypes.length == 2) {
        sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
      } else {
        throw new BuilderException("Cannot invoke SqlProvider method '" + providerMethod
          + "' with specify parameter '" + (parameterObject == null ? null : parameterObject.getClass())
          + "' because SqlProvider method arguments for '" + mapperMethod + "' is an invalid combination.");
      }
      // <2> 获得参数
      Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
      // <3> 替换掉 SQL 上的属性
      // <4> 解析出 SqlSource 对象
      return languageDriver.createSqlSource(configuration, sql, parameterType);
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error invoking SqlProvider method '" + providerMethod
          + "' with specify parameter '" + (parameterObject == null ? null : parameterObject.getClass()) + "'.  Cause: " + extractRootCause(e), e);
    }
  }

  private Throwable extractRootCause(Exception e) {
    Throwable cause = e;
    while(cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }
  //获得方法参数
  //    * 逻辑比较简单，胖友思考下。
  //* `<1.2>` 处，调用 `#extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames)` 方法，获得方法参数。代码如下：
  private Object[] extractProviderMethodArguments(Object parameterObject) {
    if (providerContext != null) {
      Object[] args = new Object[2];
      args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
      args[providerContextIndex] = providerContext;
      return args;
    } else {
      return new Object[] { parameterObject };
    }
  }

  private Object[] extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames) {
    Object[] args = new Object[argumentNames.length];
    for (int i = 0; i < args.length; i++) {
      if (providerContextIndex != null && providerContextIndex == i) {
        args[i] = providerContext;
      } else {
        args[i] = params.get(argumentNames[i]);
      }
    }
    return args;
  }

  //    * 逻辑比较简单，胖友思考下。
  //* 上面两个方法，无法理解的胖友，可以看看 `org.apache.ibatis.submitted.sqlprovider.Mapper` 和 `org.apache.ibatis.submitted.sqlprovider.OurSqlBuilder` 类。
  //* 调用 `#invokeProviderMethod(Object... args)` 方法，执行方法，生成 SQL 。代码如下：
  private String invokeProviderMethod(Object... args) throws Exception {
    Object targetObject = null;
    // 获得对象
    if (!Modifier.isStatic(providerMethod.getModifiers())) {
      targetObject = providerType.getDeclaredConstructor().newInstance();
    }
    // 反射调用方法
    CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
    return sql != null ? sql.toString() : null;
  }

  private Class<?> getProviderType(Annotation providerAnnotation, Method mapperMethod)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Class<?> type = (Class<?>) providerAnnotation.annotationType().getMethod("type").invoke(providerAnnotation);
    Class<?> value = (Class<?>) providerAnnotation.annotationType().getMethod("value").invoke(providerAnnotation);
    if (value == void.class && type == void.class) {
      throw new BuilderException("Please specify either 'value' or 'type' attribute of @"
          + providerAnnotation.annotationType().getSimpleName()
          + " at the '" + mapperMethod.toString() + "'.");
    }
    if (value != void.class && type != void.class && value != type) {
      throw new BuilderException("Cannot specify different class on 'value' and 'type' attribute of @"
          + providerAnnotation.annotationType().getSimpleName()
          + " at the '" + mapperMethod.toString() + "'.");
    }
    return value == void.class ? type : value;
  }

}
