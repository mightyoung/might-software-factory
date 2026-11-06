package com.mightyoung.softwarefactory.support;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One physical lease; deliberately implements no raw-target or vendor interface. */
final class G014JdbcGuard implements InvocationHandler {
    enum Phase { SETUP, STAGING, FINAL_VALIDATING, SEALED, COMMIT_SENT, COMMITTED, ROLLBACK_PENDING, ROLLED_BACK, UNKNOWN }
    private final G014SqlProfile profile;
    private final Connection delegate;
    private final Thread owner=Thread.currentThread();
    private final AtomicBoolean poisoned=new AtomicBoolean();
    private final Map<String,TransactionSqlSealGate.SqlTemplate> templates;
    private final Set<Child> children=Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<TransactionSqlSealGate.SqlExecution> executions=new ArrayList<>();
    final Connection connection;
    private ConnectionHolder holder;
    private Object source;
    private Phase phase=Phase.SETUP;
    private Boolean autoCommit,readOnly;
    private Integer isolation;
    private boolean closed,normalFinalReturn,constraintsAcknowledged;
    private List<org.springframework.transaction.support.TransactionSynchronization> sealedSynchronizations;
    private long validationGeneration,autoGeneration=-1,readGeneration=-1,isolationGeneration=-1;

    G014JdbcGuard(Connection delegate,Collection<TransactionSqlSealGate.SqlTemplate> templates) {
        this(G014SqlProfile.ASSET_FOUNDATION,delegate,templates);
    }
    G014JdbcGuard(G014SqlProfile profile,Connection delegate,Collection<TransactionSqlSealGate.SqlTemplate> templates) {
        this.profile=Objects.requireNonNull(profile);
        profile.requireCatalog(templates);
        this.delegate=Objects.requireNonNull(delegate);
        var selected=new HashMap<String,TransactionSqlSealGate.SqlTemplate>();
        for(var template:templates) if(selected.put(template.sql(),template)!=null) throw new IllegalArgumentException("Duplicate SQL template");
        this.templates=Map.copyOf(selected);
        this.connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},this);
    }
    void bind(Object source,ConnectionHolder holder) {
        if(phase!=Phase.SETUP || this.holder!=null || holder.getConnection()!=connection) throw reject();
        this.source=source;this.holder=holder;phase=Phase.STAGING;
    }
    G014SqlProfile profile(){return profile;}
    Phase phase(){return phase;}
    boolean poisoned(){return poisoned.get();}
    List<TransactionSqlSealGate.SqlExecution> executions(){requireBound();return List.copyOf(executions);}
    /** Trusted gate control after the staging owner's callback and resource closure. */
    void flushDeferredConstraintsIfRegistered() {
        requireBound();
        if(phase!=Phase.STAGING || !children.isEmpty())throw reject();
        var fixed=TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate(profile);
        var registered=templates.get(fixed.sql());
        // Old construction-time catalogs without deferred constraints retain their existing contract.
        if(registered==null){if(profile==G014SqlProfile.RISK_SCENARIO)throw reject();return;}
        if(!fixed.equals(registered))throw reject();
        if(constraintsAcknowledged)return;
        // Use only this lease's guarded control path; never reopen an expired business boundary.
        try(var statement=connection.prepareStatement(fixed.sql())){statement.execute();}
        catch(SQLException failure){poison();throw new IllegalStateException("G014 deferred constraint check failed",failure);}
    }
    void beginFinal() {
        requireBound();if(phase!=Phase.STAGING || !children.isEmpty()) throw reject();
        if(templates.values().stream().anyMatch(t->t.commandAcknowledgment()!=null) && !constraintsAcknowledged)throw reject();
        phase=Phase.FINAL_VALIDATING;validationGeneration++;
    }
    void sealAfterNormalReturn() {
        requireBound();
        if(phase!=Phase.FINAL_VALIDATING || !children.isEmpty() || !Boolean.FALSE.equals(autoCommit)
                || !Boolean.FALSE.equals(readOnly) || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(isolation)
                || autoGeneration!=validationGeneration || readGeneration!=validationGeneration || isolationGeneration!=validationGeneration) throw reject();
        sealedSynchronizations=TransactionSynchronizationManager.getSynchronizations();
        normalFinalReturn=true;phase=Phase.SEALED;
    }
    void checkCommit() {
        requireBound();
        if(phase!=Phase.SEALED || !normalFinalReturn || !children.isEmpty()
                || !Boolean.FALSE.equals(autoCommit) || !Boolean.FALSE.equals(readOnly)
                || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(isolation)) throw reject();
        var current=TransactionSynchronizationManager.getSynchronizations();
        if(current.size()!=sealedSynchronizations.size())throw reject();
        for(int i=0;i<current.size();i++)if(current.get(i)!=sealedSynchronizations.get(i))throw reject();
    }
    // Only the package-private manager bridge invokes these methods; no public JDBC permit exists.
    void commit() throws SQLException {
        var permit=new CommitPermit();
        commit(permit);
    }
    private final class CommitPermit {
        final G014JdbcGuard lease=G014JdbcGuard.this;
        final Thread thread=Thread.currentThread();
        private boolean consumed;
        private CommitPermit(){checkCommit();}
    }
    private void commit(CommitPermit permit)throws SQLException {
        if(permit.lease!=this || permit.thread!=owner || permit.consumed)throw reject();
        permit.consumed=true;
        checkCommit(); // immediate memory-only barrier; no callback between here and delegate.
        phase=Phase.COMMIT_SENT;
        try { delegate.commit();phase=Phase.COMMITTED; }
        catch(SQLException|RuntimeException|Error failure){phase=Phase.UNKNOWN;poison();throw failure;}
    }
    void rollback() throws SQLException {
        if(owner!=Thread.currentThread()) throw reject();
        if(phase==Phase.COMMITTED || phase==Phase.ROLLED_BACK)return;
        if(phase==Phase.UNKNOWN || phase==Phase.COMMIT_SENT){phase=Phase.UNKNOWN;poison();return;}
        phase=Phase.ROLLBACK_PENDING;
        try{delegate.rollback();phase=Phase.ROLLED_BACK;}
        catch(SQLException|RuntimeException|Error failure){phase=Phase.UNKNOWN;poison();throw failure;}
    }
    void cleanup() throws SQLException {
        if(owner!=Thread.currentThread() || !(phase==Phase.COMMITTED || phase==Phase.ROLLED_BACK)) throw reject();
        // Acknowledgment precedes every reset/return to the existing pool.
        delegate.setAutoCommit(true);delegate.close();closed=true;
    }
    void poison(){poisoned.set(true);if(holder!=null)holder.setRollbackOnly();}
    IllegalStateException reject(){poison();return new IllegalStateException("G014 transaction boundary is unavailable");}
    private void requireBound() {
        if(poisoned.get() || closed || owner!=Thread.currentThread() || holder==null
                || TransactionSynchronizationManager.getResource(source)!=holder || holder.getConnection()!=connection
                || holder.isRollbackOnly() || !holder.isSynchronizedWithTransaction()
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) throw reject();
    }
    private void active() { requireBound();if(phase!=Phase.STAGING && phase!=Phase.FINAL_VALIDATING)throw reject(); }
    private Object local(Object proxy,Method method,Object[] args,Class<?> iface) {
        return switch(method.getName()) {
            case "toString" -> "G014Guarded"+iface.getSimpleName()+"[REDACTED]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy==args[0];
            case "unwrap" -> {if(args[0]!=iface)throw reject();yield proxy;}
            case "isWrapperFor" -> {if(args[0]!=iface)throw reject();yield true;}
            default -> throw reject();
        };
    }
    private boolean localMethod(Method method){return method.getDeclaringClass()==Object.class || method.getName().equals("unwrap") || method.getName().equals("isWrapperFor");}
    @Override public Object invoke(Object proxy,Method method,Object[] incoming)throws Throwable {
        if(owner!=Thread.currentThread() || poisoned.get())throw reject();
        Object[] args=incoming==null?new Object[0]:incoming;
        if(localMethod(method))return local(proxy,method,args,Connection.class);
        if(method.getName().equals("isClosed"))return closed;
        if(phase==Phase.SETUP){
            if(owner!=Thread.currentThread() || poisoned.get())throw reject();
            String operation=method.getName();
            if(args.length==0 && Set.of("getAutoCommit","isReadOnly","getTransactionIsolation").contains(operation))return call(delegate,method,args);
            if(args.length==1 && ((operation.equals("setAutoCommit")&&Boolean.FALSE.equals(args[0]))
                    || (operation.equals("setReadOnly")&&Boolean.FALSE.equals(args[0]))
                    || (operation.equals("setTransactionIsolation")&&Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(args[0]))))
                return call(delegate,method,args);
            throw reject();
        }
        requireBound();
        String name=method.getName();
        if(args.length==0 && Set.of("getAutoCommit","isReadOnly","getTransactionIsolation").contains(name)) {
            if(phase==Phase.SEALED)return switch(name){case "getAutoCommit"->autoCommit;case "isReadOnly"->readOnly;default->isolation;};
            active();Object value=call(delegate,method,args);
            switch(name){
                case "getAutoCommit"->{autoCommit=(Boolean)value;autoGeneration=validationGeneration;}
                case "isReadOnly"->{readOnly=(Boolean)value;readGeneration=validationGeneration;}
                default->{isolation=(Integer)value;isolationGeneration=validationGeneration;}
            }
            return value;
        }
        active();
        if(name.equals("prepareStatement") && args.length==1 && args[0] instanceof String sql) {
            var template=template(sql);return new Child(call(delegate,method,args),PreparedStatement.class,null,template).proxy;
        }
        if(name.equals("createStatement") && args.length==0)return new Child(call(delegate,method,args),Statement.class,null,null).proxy;
        if(name.equals("getMetaData") && args.length==0 && phase==Phase.STAGING)return new Metadata(call(delegate,method,args),DatabaseMetaData.class).proxy;
        throw reject();
    }
    private TransactionSqlSealGate.SqlTemplate template(String sql) {
        if(phase==Phase.STAGING && constraintsAcknowledged)throw reject();
        var result=templates.get(sql);
        if(result==null || (phase==Phase.FINAL_VALIDATING&&!result.finalRead()))throw reject();
        return result;
    }
    private Object call(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){poison();throw e.getCause();}
    }
    private Object scalar(Object value) {
        if(value==null || value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof BigDecimal || value instanceof UUID || value instanceof OffsetDateTime || value instanceof Instant)return value;
        if(value instanceof byte[] bytes)return bytes.clone();
        throw reject();
    }
    private final class Child implements InvocationHandler {
        final Object raw,proxy;
        final Class<?> iface;
        final Child parent;
        final TransactionSqlSealGate.SqlTemplate prepared;
        final Map<Integer,Object> parameters=new TreeMap<>();
        final List<Map<Integer,Object>> batches=new ArrayList<>();
        boolean childClosed;
        Child(Object raw,Class<?> iface,Child parent,TransactionSqlSealGate.SqlTemplate prepared){
            this.raw=Objects.requireNonNull(raw);this.iface=iface;this.parent=parent;this.prepared=prepared;
            proxy=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},this);children.add(this);
        }
        @Override public Object invoke(Object self,Method method,Object[] incoming)throws Throwable {
            if(owner!=Thread.currentThread() || poisoned.get())throw reject();
            Object[] args=incoming==null?new Object[0]:incoming;
            if(localMethod(method))return local(self,method,args,iface);
            String name=method.getName();
            if(name.equals("getConnection")&&args.length==0&&iface!=ResultSet.class)return connection;
            if(name.equals("getStatement")&&args.length==0&&iface==ResultSet.class)return parent.proxy;
            if(name.equals("isClosed")&&args.length==0)return childClosed;
            if(name.equals("close")&&args.length==0&&childClosed)return null;
            active();if(childClosed)throw reject();
            if(name.equals("close")&&args.length==0){
                Object result=call(raw,method,args);childClosed=true;children.remove(this);batches.clear();
                for(var child:List.copyOf(children))if(child.parent==this){child.childClosed=true;children.remove(child);}
                return result;
            }
            if(iface==ResultSet.class)return result(method,args);
            if(name.equals("getMetaData")&&args.length==0&&iface==PreparedStatement.class&&phase==Phase.STAGING)
                return metadata(call(raw,method,args),ResultSetMetaData.class);
            if(name.equals("getParameterMetaData")&&args.length==0&&iface==PreparedStatement.class&&phase==Phase.STAGING)
                return metadata(call(raw,method,args),ParameterMetaData.class);
            if(name.equals("clearParameters")&&args.length==0&&iface==PreparedStatement.class){parameters.clear();return call(raw,method,args);}
            if(iface==PreparedStatement.class && name.equals("setTimestamp")) {
                if(args.length!=2 || !(args[0] instanceof Integer index) || index<1 || index>prepared.parameterCount()
                        || (args[1]!=null && !(args[1] instanceof Timestamp)))throw reject();
                Instant snapshot=args[1]==null?null:((Timestamp)args[1]).toInstant();
                parameters.put(index,snapshot);
                // Never expose the caller's mutable Timestamp to the driver or retain it as evidence.
                return call(raw,method,new Object[]{index,snapshot==null?null:Timestamp.from(snapshot)});
            }
            if(iface==PreparedStatement.class && name.equals("setObject") && args.length==2 && args[1] instanceof Timestamp timestamp) {
                if(!(args[0] instanceof Integer index) || index<1 || index>prepared.parameterCount())throw reject();
                Instant snapshot=timestamp.toInstant();
                parameters.put(index,snapshot);
                // JdbcTemplate Object[] uses this exact method; preserve its driver type inference.
                return call(raw,method,new Object[]{index,Timestamp.from(snapshot)});
            }
            if(iface==PreparedStatement.class && Set.of("setString","setBoolean","setInt","setLong","setShort","setBigDecimal","setBytes","setObject","setNull").contains(name)) {
                if(args.length!=2 || !(args[0] instanceof Integer index) || index<1 || index>prepared.parameterCount())throw reject();
                Object value=name.equals("setNull")?null:scalar(args[1]);
                if(name.equals("setNull")&&!(args[1] instanceof Integer))throw reject();
                parameters.put(index,value);Object[] copied=args.clone();if(value instanceof byte[])copied[1]=((byte[])value).clone();
                return call(raw,method,copied);
            }
            if(name.equals("addBatch")&&args.length==0&&iface==PreparedStatement.class&&phase==Phase.STAGING){
                template(prepared.sql());if(prepared.commandAcknowledgment()!=null)throw reject();
                checkedParameters();batches.add(copyParameters());return call(raw,method,args);
            }
            if(name.equals("clearBatch")&&args.length==0&&phase==Phase.STAGING){batches.clear();return call(raw,method,args);}
            if(Set.of("executeBatch","executeLargeBatch").contains(name)&&args.length==0&&phase==Phase.STAGING&&iface==PreparedStatement.class){
                template(prepared.sql());if(prepared.commandAcknowledgment()!=null)throw reject();
                Object result=call(raw,method,args);
                long[] counts=result instanceof int[] ints?Arrays.stream(ints).asLongStream().toArray():(long[])result;
                if(counts.length!=batches.size())throw reject();
                for(int i=0;i<counts.length;i++)record(prepared,batches.get(i),counts[i]);batches.clear();return result;
            }
            if(Set.of("execute","executeQuery","executeUpdate","executeLargeUpdate").contains(name)){
                TransactionSqlSealGate.SqlTemplate sql;
                if(iface==PreparedStatement.class&&args.length==0){sql=template(prepared.sql());checkedParameters();}
                else if(iface==Statement.class&&args.length==1&&args[0] instanceof String text){sql=template(text);if(sql.parameterCount()!=0)throw reject();}
                else throw reject();
                if(phase==Phase.FINAL_VALIDATING&&!(name.equals("executeQuery")||name.equals("execute")))throw reject();
                if(sql.commandAcknowledgment()!=null && !name.equals("execute"))throw reject();
                Object result=call(raw,method,args);
                if(sql.commandAcknowledgment()!=null){
                    if(phase!=Phase.STAGING || !Boolean.FALSE.equals(result) || constraintsAcknowledged)throw reject();
                    long count=(Long)call(raw,Statement.class.getMethod("getLargeUpdateCount"),new Object[0]);
                    // Pinned PgJDBC 42.7.14 parses the SET completion tag as zero. Preserve it as observed.
                    if(count!=0 || call(raw,Statement.class.getMethod("getResultSet"),new Object[0])!=null)throw reject();
                    executions.add(new TransactionSqlSealGate.SqlExecution(null,Map.of(),count,sql.commandAcknowledgment()));
                    constraintsAcknowledged=true;
                    return result;
                }
                if(phase==Phase.FINAL_VALIDATING&&name.equals("execute")&&!Boolean.TRUE.equals(result))throw reject();
                if(result instanceof ResultSet)return new Child(result,ResultSet.class,this,null).proxy;
                if(result instanceof Number count)record(sql,copyParameters(),count.longValue());
                else if(result instanceof Boolean rows&&!rows){
                    // JDBC execute's success is observed from the actual update count, not an attempted INSERT.
                    long count=(Long)call(raw,Statement.class.getMethod("getLargeUpdateCount"),new Object[0]);
                    record(sql,copyParameters(),count);
                }
                return result;
            }
            if(Set.of("getResultSet","getGeneratedKeys").contains(name)&&args.length==0){
                Object result=call(raw,method,args);return result==null?null:new Child(result,ResultSet.class,this,null).proxy;
            }
            if(Set.of("getUpdateCount","getLargeUpdateCount").contains(name)&&args.length==0)return call(raw,method,args);
            // Framework methods require explicit source review before extending this finite list.
            if(Set.of("setQueryTimeout","setFetchSize","setMaxRows").contains(name)&&args.length==1&&args[0] instanceof Integer count&&count>=0)
                return call(raw,method,args);
            throw reject();
        }
        private Object result(Method method,Object[] args)throws Throwable {
            String name=method.getName();
            if(Set.of("next","wasNull").contains(name)&&args.length==0)return call(raw,method,args);
            if(name.equals("getMetaData")&&args.length==0&&phase==Phase.STAGING)return metadata(call(raw,method,args),ResultSetMetaData.class);
            if(Set.of("getString","getBoolean","getInt","getLong","getShort","getBigDecimal","getBytes","getObject").contains(name)
                    &&(args.length==1 || (name.equals("getObject")&&args.length==2&&args[1] instanceof Class<?> type
                    && Set.of(UUID.class,OffsetDateTime.class,Instant.class,String.class,Integer.class,Long.class,Short.class,Boolean.class,BigDecimal.class).contains(type)))
                    &&(args[0] instanceof Integer || args[0] instanceof String))return scalar(call(raw,method,args));
            throw reject();
        }
        private void checkedParameters(){if(parameters.size()!=prepared.parameterCount())throw reject();}
        private Map<Integer,Object> copyParameters(){var copy=new TreeMap<Integer,Object>();parameters.forEach((k,v)->copy.put(k,v instanceof byte[] bytes?bytes.clone():v));return Collections.unmodifiableMap(copy);}
    }
    private void record(TransactionSqlSealGate.SqlTemplate sql,Map<Integer,Object> parameters,long count) {
        if(sql.effect()!=null){if(phase!=Phase.STAGING || count<0)throw reject();executions.add(new TransactionSqlSealGate.SqlExecution(sql.effect(),parameters,count));}
    }
    private Object metadata(Object value,Class<?> type){return value==null?null:new Metadata(value,type).proxy;}
    private static final Set<String> DB_METADATA=Set.of("getDatabaseProductName","getDatabaseProductVersion","getDatabaseMajorVersion","getDatabaseMinorVersion","getDriverName","getDriverVersion","getDriverMajorVersion","getDriverMinorVersion","supportsTransactions","supportsBatchUpdates","supportsSavepoints","getJDBCMajorVersion","getJDBCMinorVersion");
    private static final Set<String> RESULT_METADATA=Set.of("getColumnCount","getColumnLabel","getColumnName","getColumnType","getColumnTypeName","getColumnClassName","getPrecision","getScale","isNullable","isSigned","isAutoIncrement","isCaseSensitive","isCurrency","isReadOnly","isSearchable","isWritable","isDefinitelyWritable","getCatalogName","getSchemaName","getTableName","getColumnDisplaySize");
    private static final Set<String> PARAMETER_METADATA=Set.of("getParameterCount","getParameterType","getParameterTypeName","getParameterClassName","getParameterMode","isNullable","isSigned","getPrecision","getScale");
    private final class Metadata implements InvocationHandler {
        final Object raw,proxy;final Class<?> iface;
        Metadata(Object raw,Class<?> iface){this.raw=raw;this.iface=iface;proxy=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},this);}
        @Override public Object invoke(Object self,Method method,Object[] incoming)throws Throwable {
            if(owner!=Thread.currentThread() || poisoned.get())throw reject();
            Object[] args=incoming==null?new Object[0]:incoming;
            if(localMethod(method))return local(self,method,args,iface);
            if(iface==DatabaseMetaData.class&&method.getName().equals("getConnection")&&args.length==0)return connection;
            active();if(phase!=Phase.STAGING)throw reject();
            Set<String> allowed=iface==DatabaseMetaData.class?DB_METADATA:iface==ResultSetMetaData.class?RESULT_METADATA:PARAMETER_METADATA;
            if(!allowed.contains(method.getName()) || !(args.length==0 || args.length==1&&args[0] instanceof Integer))throw reject();
            return scalar(call(raw,method,args));
        }
    }
}
