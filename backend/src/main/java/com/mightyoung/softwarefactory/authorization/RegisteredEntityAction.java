package com.mightyoung.softwarefactory.authorization;

/** Closed platform actions. Transport input cannot register or select an arbitrary permission. */
public enum RegisteredEntityAction {
    WORKSHOP_READ("workshop", "read"),
    WORKSHOP_CREATE("workshop", "create"),
    WORKSHOP_UPDATE("workshop", "update"),
    WORKSHOP_ARCHIVE("workshop", "archive"),
    PRODUCTION_LINE_READ("production_line", "read"),
    PRODUCTION_LINE_CREATE("production_line", "create"),
    PRODUCTION_LINE_UPDATE("production_line", "update"),
    PRODUCTION_LINE_ARCHIVE("production_line", "archive"),
    PROCESS_STAGE_READ("process_stage", "read"),
    PROCESS_STAGE_CREATE("process_stage", "create"),
    PROCESS_STAGE_UPDATE("process_stage", "update"),
    PROCESS_STAGE_ARCHIVE("process_stage", "archive"),
    EQUIPMENT_READ("equipment", "read"),
    EQUIPMENT_CREATE("equipment", "create"),
    EQUIPMENT_UPDATE("equipment", "update"),
    EQUIPMENT_ARCHIVE("equipment", "archive"),
    WORK_UNIT_READ("work_unit", "read"),
    WORK_UNIT_CREATE("work_unit", "create"),
    WORK_UNIT_UPDATE("work_unit", "update"),
    WORK_UNIT_ARCHIVE("work_unit", "archive"),
    HAZARD_SOURCE_READ("hazard_source", "read"),
    HAZARD_SOURCE_CREATE("hazard_source", "create"),
    HAZARD_SOURCE_UPDATE("hazard_source", "update"),
    RISK_SCENARIO_READ("risk_scenario", "read"),
    RISK_SCENARIO_CREATE("risk_scenario", "create"),
    RISK_SCENARIO_UPDATE("risk_scenario", "update"),
    LINE_WORKSHOP_BIND("line_workshop", "bind"),
    LINE_WORKSHOP_END("line_workshop", "end"),
    STAGE_EQUIPMENT_BIND("stage_equipment", "bind"),
    STAGE_EQUIPMENT_END("stage_equipment", "end"),
    RESPONSIBILITY_BINDING_BIND("responsibility_binding", "bind"),
    RESPONSIBILITY_BINDING_END("responsibility_binding", "end");

    private final String resourceType;
    private final String action;
    RegisteredEntityAction(String resourceType, String action) {
        this.resourceType = resourceType;
        this.action = action;
    }
    public String resourceType() { return resourceType; }
    public String action() { return action; }
    public String permissionCode() { return resourceType + "." + action; }
    public boolean isRead() { return action.equals("read"); }
}
