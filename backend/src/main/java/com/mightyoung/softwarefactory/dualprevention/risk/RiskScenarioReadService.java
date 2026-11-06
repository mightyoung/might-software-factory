package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;

/** Private risk RO/RR graph. Returns final JSON bytes; HTTP owns the separately bounded headers. */
public final class RiskScenarioReadService {
    private final RiskScenarioReadRepository repository;
    private final HumanSessionAuthorityTransaction authority;
    private final EntityActionAuthorization actions;
    private final DataSource source;
    private final TenantApplicationReadRepository applications;
    private final TransactionTemplate transaction;
    public RiskScenarioReadService(RiskScenarioReadRepository repository,HumanSessionAuthorityTransaction authority,
            EntityActionAuthorization actions,PlatformTransactionManager readManager,DataSource source,TenantApplicationReadRepository applications) {
        this.repository=Objects.requireNonNull(repository);this.authority=Objects.requireNonNull(authority);this.actions=Objects.requireNonNull(actions);
        this.source=Objects.requireNonNull(source);this.repository.requireSource(source);this.applications=Objects.requireNonNull(applications);
        transaction=new TransactionTemplate(Objects.requireNonNull(readManager));transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(10);
    }
    /** Bytes are owned by this response; callers receive a copy and must send without JSON reserialization. */
    public static final class EncodedResponse {
        private final byte[] body;private final int length;
        private EncodedResponse(byte[] owned){this(owned,owned.length);}
        private EncodedResponse(byte[] owned,int length){body=Objects.requireNonNull(owned);if(length<0||length>owned.length)throw new IllegalArgumentException();this.length=length;}
        public byte[] body(){return Arrays.copyOf(body,length);}
        public int size(){return length;}
        /** Writes the owned range exactly once per call, without a defensive full-body copy or stream ownership transfer. */
        public void writeTo(java.io.OutputStream output)throws java.io.IOException{Objects.requireNonNull(output).write(body,0,length);}
    }
    public EncodedResponse detail(CapturedHumanRequest request,UUID id,UUID trace) {
        return inReadSnapshot(request,snapshot->{var scope=snapshot.authorize(RISK_SCENARIO_READ);var root=parent(snapshot,scope,id);
            Hazards hazards=hazards(snapshot,scope,id,root.version());
            return response(new Detail(root.id(),root.organizationId(),root.version(),root.code(),root.description(),root.triggeringConditions(),root.exposureDescription(),root.consequence(),root.createdAt(),root.updatedAt(),AssessmentStatus.UNASSESSED,null,null,null,hazards),trace,ResponseBudget.DETAIL);
        });
    }
    public EncodedResponse historicalSnapshot(CapturedHumanRequest request,UUID id,long version,UUID hazard,UUID trace) {
        RiskScenarioContracts.version(version);Objects.requireNonNull(hazard);
        return inReadSnapshot(request,snapshot->{var scope=snapshot.authorize(RISK_SCENARIO_READ);parent(snapshot,scope,id);
            if(!repository.revisionExists(snapshot,scope,id,version))throw new Failure(404);
            var keys=keys(snapshot,scope,id,version);
            if(keys.stream().noneMatch(k->k.hazardId().equals(hazard))||!allTargets(snapshot,scope,keys))throw new Failure(404);
            var found=repository.historicalSnapshot(snapshot,scope,id,version,hazard).orElseThrow(()->new Failure(503));
            return response(Map.of("hazardSnapshot",found),trace,ResponseBudget.HISTORICAL_SNAPSHOT);
        });
    }
    public EncodedResponse history(CapturedHumanRequest request,UUID id,HistoryRequest input,UUID trace) {
        Objects.requireNonNull(input);
        return inReadSnapshot(request,snapshot->{var scope=snapshot.authorize(RISK_SCENARIO_READ);parent(snapshot,scope,id);
            if(!repository.historyAnchor(snapshot,scope,id,input.after()))throw invalid();
            var candidates=repository.revisionKeys(snapshot,scope,id,input);var sink=new PageSink(ResponseBudget.HISTORY,"{\"scenarioId\":\""+id+"\",\"items\":[");
            HistoryCursor last=null;int accepted=0;
            for(int i=0;i<Math.min(input.pageSize(),candidates.size());i++) {
                var key=candidates.get(i);var hazards=hazards(snapshot,scope,id,key.version());
                var row=repository.revision(snapshot,scope,id,key.version(),hazards).orElseThrow(()->new Failure(503));
                if(!key.commandId().equals(row.commandId())||!key.occurredAt().equals(row.occurredAt()))throw new Failure(503);
                var cursor=new HistoryCursor(row.occurredAt(),row.commandId());byte[] item=encodeResponse(row,ResponseBudget.ITEM);
                if(!sink.add(item,historyTail(true,cursor,trace)))break;
                last=cursor;accepted++;
            }
            boolean more=accepted<candidates.size();return sink.finish(historyTail(more,more?last:null,trace));
        });
    }
    public EncodedResponse eligibleHazards(CapturedHumanRequest request,SelectorRequest input,UUID trace) {
        Objects.requireNonNull(input);
        return inReadSnapshot(request,snapshot->{var owner=snapshot.authorize(input.use()==SelectorUse.CREATE?RISK_SCENARIO_CREATE:RISK_SCENARIO_UPDATE);
            UUID organization=input.use()==SelectorUse.CREATE?input.organizationId():parent(snapshot,owner,input.scenarioId()).organizationId();
            if(!repository.organization(snapshot,owner,organization))throw new Failure(404);
            var target=snapshot.authorize(HAZARD_SOURCE_READ);
            if(!repository.selectorAnchor(snapshot,target,input))throw invalid();
            var candidates=repository.eligibleKeys(snapshot,target,input);var sink=new PageSink(ResponseBudget.SELECTOR,"{\"items\":[");UUID last=null;int accepted=0;
            for(int i=0;i<Math.min(input.limit(),candidates.size());i++) {
                UUID id=candidates.get(i);var item=repository.candidate(snapshot,target,id).orElseThrow(()->new Failure(503));
                if(!id.equals(item.id())||!target.organizationIds().contains(item.organizationId()))throw new Failure(503);
                if(!sink.add(encodeResponse(item,ResponseBudget.ITEM),selectorTail(true,id,trace)))break;
                last=id;accepted++;
            }
            boolean more=accepted<candidates.size();return sink.finish(selectorTail(more,more?last:null,trace));
        });
    }
    public EncodedResponse query(CapturedHumanRequest request,QueryRequest input,UUID trace) {
        RiskScenarioReadRepository.validate(input);
        return inReadSnapshot(request,snapshot->{var scope=snapshot.authorize(RISK_SCENARIO_READ);
            byte[] fingerprint=RiskScenarioReadCursorCodec.fingerprint(scope,snapshot.applicationVersion(scope),input);
            RiskScenarioReadRepository.QueryKey anchor=null;
            if(input.cursor()!=null)anchor=repository.queryAnchor(snapshot,scope,input,RiskScenarioReadCursorCodec.decode(input.cursor(),fingerprint)).orElseThrow(RiskScenarioContracts::invalid);
            var candidates=repository.queryKeys(snapshot,scope,input,anchor);long total=repository.count(snapshot,scope,input);
            var sink=new PageSink(ResponseBudget.QUERY,"{\"items\":[");String last=null;int accepted=0;
            for(int i=0;i<Math.min(input.size(),candidates.size());i++) {
                var key=candidates.get(i);var value=repository.projection(snapshot,scope,input,key);
                String cursor=RiskScenarioReadCursorCodec.encode(fingerprint,new RiskScenarioReadCursorCodec.Anchor(key.id(),key.version()));
                if(!sink.add(encodeResponse(value,ResponseBudget.ITEM),queryTail(input,total,true,cursor,trace)))break;
                last=cursor;accepted++;
            }
            boolean more=accepted<candidates.size();return sink.finish(queryTail(input,total,more,more?last:null,trace));
        });
    }
    private RiskScenarioReadRepository.Root parent(ReadSnapshot snapshot,ActionScope scope,UUID id){Objects.requireNonNull(id);return repository.parent(snapshot,scope,id).orElseThrow(()->new Failure(404));}
    private List<RiskScenarioReadRepository.SnapshotKey> keys(ReadSnapshot snapshot,ActionScope scope,UUID id,long version){
        var keys=repository.snapshotKeys(snapshot,scope,id,version);
        if(keys.isEmpty()||keys.size()>100||keys.stream().map(RiskScenarioReadRepository.SnapshotKey::hazardId).distinct().count()!=keys.size())throw new Failure(503);
        return keys;
    }
    private Hazards hazards(ReadSnapshot snapshot,ActionScope scope,UUID id,long version){
        var keys=keys(snapshot,scope,id,version);if(!allTargets(snapshot,scope,keys))return new RestrictedHazards();
        var summaries=repository.summaries(snapshot,scope,id,version);
        if(summaries.size()!=keys.size()||!new HashSet<>(summaries.stream().map(HazardSummary::id).toList()).equals(new HashSet<>(keys.stream().map(RiskScenarioReadRepository.SnapshotKey::hazardId).toList())))throw new Failure(503);
        try{return new CompleteHazards(summaries);}catch(Failure malformed){throw new Failure(503);}
    }
    private boolean allTargets(ReadSnapshot snapshot,ActionScope parent,List<RiskScenarioReadRepository.SnapshotKey> keys){
        ActionScope target;
        try {target=snapshot.authorize(HAZARD_SOURCE_READ);}catch(AccessDeniedException denied){snapshot.connection(parent);return false;}
        boolean complete=true;
        for(var key:keys)if(!repository.targetVisible(snapshot,target,key.hazardId()))complete=false;
        snapshot.connection(parent);return complete;
    }
    <T> T inReadSnapshot(CapturedHumanRequest request,Function<ReadSnapshot,T> work) {
        Objects.requireNonNull(request);Objects.requireNonNull(work);
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.hasResource(source))throw new Failure(503);
        request.requireCurrent();try{return transaction.execute(status->{repository.setTenant(request.context().tenantId());return authority.withRead(request,boundary->{
            var snapshot=new ReadSnapshot(request,boundary);try{snapshot.requireActive();T result=work.apply(snapshot);snapshot.requireActive();return result;}finally{snapshot.active=false;}
        });});}catch(org.springframework.transaction.TransactionException failure){throw new Failure(503);}
    }
    /** Risk-issued, thread/holder/physical-connection/lifetime-bound; never shares an asset capsule. */
    final class ReadSnapshot {
        private final CapturedHumanRequest request;private final HumanSessionAuthorityTransaction.ReadBoundary boundary;
        private final Thread thread=Thread.currentThread();private final ConnectionHolder holder;private final Connection connection;
        private final Set<ActionScope> issued=Collections.newSetFromMap(new IdentityHashMap<>());private volatile boolean active=true;
        private ReadSnapshot(CapturedHumanRequest request,HumanSessionAuthorityTransaction.ReadBoundary boundary){this.request=request;this.boundary=boundary;
            if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder current))throw new Failure(503);holder=current;connection=holder.getConnection();}
        Failure invalidate(){if(active){active=false;holder.setRollbackOnly();}return new Failure(503);}
        void requireActive(){if(!active)throw new Failure(503);try{
            if(thread!=Thread.currentThread()||TransactionSynchronizationManager.getResource(source)!=holder||holder.getConnection()!=connection||holder.isRollbackOnly()||!holder.isSynchronizedWithTransaction()
                    ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalidate();
            request.requireCurrent();authority.requireRead(request,boundary,source,connection);
        }catch(RuntimeException|Error failure){invalidate();throw failure;}}
        ActionScope authorize(RegisteredEntityAction action){requireActive();if(action!=RISK_SCENARIO_READ&&action!=RISK_SCENARIO_CREATE&&action!=RISK_SCENARIO_UPDATE&&action!=HAZARD_SOURCE_READ)throw invalidate();
            var scope=actions.requireRead(request,boundary,action);requireActive();if(scope.action()!=action)throw invalidate();issued.add(scope);return scope;}
        Connection connection(ActionScope scope){requireActive();if(!issued.contains(scope))throw invalidate();actions.requireCurrent(scope);actions.requireSqlConnection(scope,source,connection);requireActive();return connection;}
        long applicationVersion(ActionScope scope){connection(scope);var app=applications.findCurrentInCaller(request.context().tenantId(),"safety").filter(value->value.enabled()).orElseThrow(this::invalidate);connection(scope);return app.version();}
    }
    private static Metadata metadata(UUID trace){Objects.requireNonNull(trace);return new Metadata(trace.toString(),trace.toString(),SCHEMA_VERSION);}
    private static EncodedResponse response(Object data,UUID trace,ResponseBudget budget){return new EncodedResponse(encodeResponse(new Response<>(data,metadata(trace)),budget));}
    private static String json(Object value){return new String(encodeResponse(value,ResponseBudget.COMMAND),StandardCharsets.UTF_8);}
    private static String historyTail(boolean more,HistoryCursor cursor,UUID trace){if(more&&cursor==null)throw Failure.responseBudgetExceeded();return "],\"hasMore\":"+more+(more?",\"nextCursor\":"+json(cursor):"")+"},\"meta\":"+json(metadata(trace))+"}";}
    private static String selectorTail(boolean more,UUID after,UUID trace){if(more&&after==null)throw Failure.responseBudgetExceeded();return "],\"hasMore\":"+more+(more?",\"nextAfter\":\""+after+"\"":"")+"},\"meta\":"+json(metadata(trace))+"}";}
    private static String queryTail(QueryRequest input,long total,boolean more,String cursor,UUID trace){if(more&&cursor==null)throw Failure.responseBudgetExceeded();return "],\"page\":"+json(new QueryPageInfo(input.size(),input.number(),more,cursor,total))+"},\"meta\":"+json(metadata(trace))+"}";}
    /** Only selected encoded bytes are retained; no page-sized collection of large DTOs. */
    private static final class PageSink {
        private final byte[] buffer;private int position,items;
        PageSink(ResponseBudget budget,String prefix){buffer=new byte[budget.bytes()];append(("{\"data\":"+prefix).getBytes(StandardCharsets.UTF_8));}
        boolean add(byte[] item,String provisionalTail){byte[] tail=provisionalTail.getBytes(StandardCharsets.UTF_8);if((long)position+(items==0?0:1)+item.length+tail.length>buffer.length){if(items==0)throw Failure.responseBudgetExceeded();return false;}
            if(items>0)buffer[position++]=',';append(item);items++;return true;}
        EncodedResponse finish(String tail){append(tail.getBytes(StandardCharsets.UTF_8));return new EncodedResponse(buffer,position);}
        private void append(byte[] bytes){if((long)position+bytes.length>buffer.length)throw Failure.responseBudgetExceeded();System.arraycopy(bytes,0,buffer,position,bytes.length);position+=bytes.length;}
    }
}
