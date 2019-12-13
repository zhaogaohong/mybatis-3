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
package org.apache.ibatis.executor;

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 实现 Executor 接口，支持二级缓存的 Executor 的实现类。
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

  /**
   * 被委托的 Executor 对象
   */
  private final Executor delegate;
  /**
   * TransactionalCacheManager 对象
   * tcm 属性，TransactionalCacheManager 对象，支持事务的缓存管理器。因为二级缓存是支持跨 Session 进行共享，
   * 此处需要考虑事务，那么，必然需要做到事务提交时，
   * 才将当前事务中查询时产生的缓存，同步到二级缓存中。这个功能，就通过 TransactionalCacheManager 来实现。
   */
  private final TransactionalCacheManager tcm = new TransactionalCacheManager();

  public CachingExecutor(Executor delegate) {
    // <1>
    this.delegate = delegate;
    // <2> 设置 delegate 被当前执行器所包装
    delegate.setExecutorWrapper(this);
  }

  @Override
  public Transaction getTransaction() {
    return delegate.getTransaction();
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      //issues #499, #524 and #573
      // 如果强制回滚，则回滚 TransactionalCacheManager
      if (forceRollback) {
        tcm.rollback();
      } else {
        // 如果强制提交，则提交 TransactionalCacheManager
        tcm.commit();
      }
    } finally {
      // 执行 delegate 对应的方法
      delegate.close(forceRollback);
    }
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public int update(MappedStatement ms, Object parameterObject) throws SQLException {
    // 如果需要清空缓存，则进行清空
    flushCacheIfRequired(ms);
    // 执行 delegate 对应的方法
    return delegate.update(ms, parameterObject);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    // 获得 BoundSql 对象
    //根据传入的参数动态获得SQL语句，最后返回用BoundSql对象表示
    BoundSql boundSql = ms.getBoundSql(parameterObject);
    // 创建 CacheKey 对象     //为本次查询创建缓存的Key
    CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
    // 查询
    return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    // 如果需要清空缓存，则进行清空
    flushCacheIfRequired(ms);
    // 执行 delegate 对应的方法
    return delegate.queryCursor(ms, parameter, rowBounds);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
      throws SQLException {
    // <1>
    Cache cache = ms.getCache();
    if (cache != null) { // <2>
      // <2.1> 如果需要清空缓存，则进行清空
      flushCacheIfRequired(ms);
      if (ms.isUseCache() && resultHandler == null) {
        // 暂时忽略，存储过程相关
        ensureNoOutParams(ms, boundSql);
        // <2.3> 从二级缓存中，获取结果
        @SuppressWarnings("unchecked")
        List<E> list = (List<E>) tcm.getObject(cache, key);
        if (list == null) {
          // <2.4.1> 如果不存在，则从数据库中查询
          list = delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
          // <2.4.2> 缓存结果到二级缓存中
          tcm.putObject(cache, key, list); // issue #578 and #116
        }
        // <2.5> 如果存在，则直接返回结果
        return list;
      }
    }
    // <3> 不使用缓存，则从数据库中查询
    return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return delegate.flushStatements();
  }

  @Override
  public void commit(boolean required) throws SQLException {
    // 执行 delegate 对应的方法
    delegate.commit(required);
    // 提交 TransactionalCacheManager
    tcm.commit();
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    try {
      // 执行 delegate 对应的方法
      delegate.rollback(required);
    } finally {
      if (required) {
        // 回滚 TransactionalCacheManager
        tcm.rollback();
      }
    }
  }

  private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
        if (parameterMapping.getMode() != ParameterMode.IN) {
          throw new ExecutorException("Caching stored procedures with OUT params is not supported.  Please configure useCache=false in " + ms.getId() + " statement.");
        }
      }
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return delegate.isCached(ms, key);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    delegate.deferLoad(ms, resultObject, property, key, targetType);
  }

  @Override
  public void clearLocalCache() {
    delegate.clearLocalCache();
  }

  private void flushCacheIfRequired(MappedStatement ms) {
    Cache cache = ms.getCache();
    if (cache != null && ms.isFlushCacheRequired()) {
      tcm.clear(cache);
    }
  }

  @Override
  public void setExecutorWrapper(Executor executor) {
    throw new UnsupportedOperationException("This method should not be called");
  }

}
