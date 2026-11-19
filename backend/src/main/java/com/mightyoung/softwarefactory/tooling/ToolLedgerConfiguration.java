package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.applicationregistry.navigation.ApplicationContribution;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.*;
import com.mightyoung.softwarefactory.ledger.*;
import java.util.*;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskClaim;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static com.mightyoung.softwarefactory.ledger.LedgerDefinition.Type;
import static com.mightyoung.softwarefactory.ledger.LedgerDefinition.Use;

/** Ordinary entity registration; the generic ledger owns transport, validation and persistence. */
@Configuration(proxyBeanMethods=false)
public class ToolLedgerConfiguration {
    @Bean WorkflowTaskClaim.Pool receiptTaskPool() {
        return new WorkflowTaskClaim.Pool("tool_loan", "toolLoanV1", "receive");
    }
    @Bean public LedgerBinding toolLedgerBinding() {
        var policy=new ToolScopePolicy();
        return new LedgerBinding(definition(),new LedgerBinding.Storage("tool",Map.of(
                "code","code","name","name","model","model","description","description",
                "custodyScopeId","custody_scope_id","organizationId","organization_id","itemState","item_state"),
                Map.of("itemState","NORMAL"),Set.of("organizationId"),Set.of("organizationId","itemState")),
                policy,Map.of("custodyScope",new LedgerBinding.ReferenceRegistration(Set.of(Use.CREATE),Set.of(),policy::queryScopes)));
    }
    @Bean public ApplicationContribution toolingApplication() {
        return new ApplicationContribution("tooling","工装管理",List.of(new ApplicationContribution.Node(
                "tools","正常工装档案","apps/tooling/entities/tool","tool",List.of("tool.read","tool.create","tool.update")),
                new ApplicationContribution.Node("loans", "我的工装借用单", "apps/tooling/entities/tool_loan", "tool_loan",
                        List.of("tool_loan.read", "tool_loan.create", "tool_loan.update", "tool_loan.issue", "tool_loan.receive"))));
    }
    static LedgerDefinition definition() {
        Map<String,LedgerDefinition.Field> fields=Map.of(
            "code",field("工装编码",Type.TEXT,false),"name",field("名称",Type.TEXT,false),
            "model",field("型号",Type.TEXT,true),"description",field("说明",Type.MULTILINE_TEXT,true),
            "custodyScopeId",field("保管范围",Type.REFERENCE,false),"organizationId",field("责任组织",Type.UUID,false),
            "itemState",new LedgerDefinition.Field("档案状态",Type.ENUM,false,Map.of("NORMAL","正常","DAMAGED","损坏","LOST","遗失","RETIRED","停用")));
        Map<String,LedgerDefinition.InputField> inputs=Map.of(
            "code",input("工装编码",Type.TEXT,true,64,null,Set.of(Use.CREATE)),
            "name",input("名称",Type.TEXT,true,200,null,Set.of(Use.CREATE,Use.EDIT)),
            "model",input("型号",Type.TEXT,false,200,null,Set.of(Use.CREATE,Use.EDIT)),
            "description",input("说明",Type.MULTILINE_TEXT,false,4000,null,Set.of(Use.CREATE,Use.EDIT)),
            "custodyScopeId",input("保管范围",Type.REFERENCE,true,0,"custodyScope",Set.of(Use.CREATE)));
        Map<String,FieldSchema> query=Map.of(
            "id",schema(FieldType.UUID,false,true,0,Set.of(FilterOperator.EQ,FilterOperator.IN),Set.of()),
            "code",schema(FieldType.TEXT,false,true,64,Set.of(FilterOperator.EQ,FilterOperator.CONTAINS),Set.of()),
            "name",schema(FieldType.TEXT,false,true,200,Set.of(FilterOperator.EQ,FilterOperator.CONTAINS),Set.of()),
            "model",schema(FieldType.TEXT,true,true,200,Set.of(FilterOperator.EQ,FilterOperator.CONTAINS,FilterOperator.IS_NULL,FilterOperator.IS_NOT_NULL),Set.of()),
            "description",schema(FieldType.TEXT,true,false,4000,Set.of(FilterOperator.CONTAINS,FilterOperator.IS_NULL,FilterOperator.IS_NOT_NULL),Set.of()),
            "custodyScopeId",schema(FieldType.UUID,false,true,0,Set.of(FilterOperator.EQ,FilterOperator.IN),Set.of()),
            "organizationId",schema(FieldType.UUID,false,true,0,Set.of(FilterOperator.EQ,FilterOperator.IN),Set.of()),
            "itemState",schema(FieldType.ENUM,false,true,0,Set.of(FilterOperator.EQ,FilterOperator.IN),Set.of("NORMAL","DAMAGED","LOST","RETIRED")));
        return new LedgerDefinition("tooling","tool","1","正常工装档案",fields,inputs,
            new EntitySchema("tool","1","id",List.of("code","name","model","custodyScopeId","itemState"),query),
            new LedgerDefinition.Layout(List.of("code","name","model","itemState"),List.of("code","name","model","description","custodyScopeId","organizationId","itemState"),
                List.of("code","name","model","description","custodyScopeId"),List.of("name","model","description"),List.of("code","name","model","custodyScopeId","itemState")),
            new LedgerDefinition.Actions(new LedgerDefinition.Action("查看","tool.read"),new LedgerDefinition.Action("新增","tool.create"),new LedgerDefinition.Action("编辑","tool.update")),
            Map.of("custodyScope",new LedgerDefinition.Reference("保管范围")));
    }
    private static LedgerDefinition.Field field(String label,Type type,boolean nullable) { return new LedgerDefinition.Field(label,type,nullable,Map.of()); }
    private static LedgerDefinition.InputField input(String label,Type type,boolean required,int length,String reference,Set<Use> uses) { return new LedgerDefinition.InputField(label,type,required,length,List.of(),reference,uses); }
    private static FieldSchema schema(FieldType type,boolean nullable,boolean sortable,int length,Set<FilterOperator> operators,Set<String> enums) { return new FieldSchema(type,nullable,true,sortable,operators,length,enums); }
}
