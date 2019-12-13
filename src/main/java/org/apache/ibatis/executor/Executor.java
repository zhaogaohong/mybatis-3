/**
 *    Copyright 2009-2015 the original author or authors.
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

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * SQL 执行的流程:
 * 对应 executor 和 cursor 模块。前者对应执行器，后者对应执行结果的游标。
 *
 * SQL 语句的执行涉及多个组件 ，其中比较重要的是 Executor、StatementHandler、ParameterHandler 和 ResultSetHandler 。
 *
 * Executor 主要负责维护一级缓存和二级缓存，并提供事务管理的相关操作，它会将数据库相关操作委托给 StatementHandler完成。
 * StatementHandler 首先通过 ParameterHandler 完成 SQL 语句的实参绑定，然后通过 java.sql.Statement 对象执行 SQL 语句并得到结果集，
 * 最后通过 ResultSetHandler 完成结果集的映射，得到结果对象并返回。
 *
 * 执行器接口
 * @author Clinton Begin
 */
public interface Executor {
  // 空 ResultHandler 对象的枚举
  ResultHandler NO_RESULT_HANDLER = null;

  // 更新 or 插入 or 删除，由传入的 MappedStatement 的 SQL 所决定
  int update(MappedStatement ms, Object parameter) throws SQLException;

  // 查询，带 ResultHandler + CacheKey + BoundSql
  <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql) throws SQLException;
  // 查询，带 ResultHandler
  <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException;
  // 查询，返回值为 Cursor
  <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException;
  // 刷入批处理语句
  List<BatchResult> flushStatements() throws SQLException;
  // 提交事务
  void commit(boolean required) throws SQLException;
  // 回滚事务
  void rollback(boolean required) throws SQLException;
  // 创建 CacheKey 对象
  CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql);
  // 判断是否缓存
  boolean isCached(MappedStatement ms, CacheKey key);
  // 清除本地缓存
  void clearLocalCache();
  // 延迟加载
  void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType);
  // 获得事务
  Transaction getTransaction();
  // 关闭事务
  void close(boolean forceRollback);
  // 判断事务是否关闭
  boolean isClosed();
  // 设置包装的 Executor 对象
  void setExecutorWrapper(Executor executor);

}
