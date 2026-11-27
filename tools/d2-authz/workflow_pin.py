"""Test-only validation for the readback of the fresh AU-08 Flowable deployment.

This only observes Flowable through an independently verified fixture connection;
it never connects, deploys, provisions or grants permissions.
"""

from hashlib import sha256

from bootstrap_inventory import RESOURCES, SOURCES, digest
from fixture_scope import fixture_transaction, synthetic_tenant_id

RESOURCE = "processes/tool-loan-v1.bpmn20.xml"
DEFINITION_KEY = "toolLoanV1"


def check_workflow_pin(root, instance, definitions):
    """Return only the verified tenant and values expected by the app's pin."""
    tenant = synthetic_tenant_id(instance)
    if not isinstance(definitions, (tuple, list)) or len(definitions) != 1:
        raise ValueError("Expected exactly one AU-08 workflow deployment")
    definition, = definitions
    if not isinstance(definition, dict) or (
        definition.get("tenant") != tenant
        or definition.get("key") != DEFINITION_KEY
        or type(definition.get("version")) is not int
        or definition["version"] != 1
        or definition.get("active") is not True
        or definition.get("resource_name") != RESOURCE
    ):
        raise ValueError("AU-08 workflow deployment does not match the pristine fixture")
    definition_id = definition.get("definition_id")
    resource = definition.get("resource")
    if not isinstance(definition_id, str) or not definition_id.strip() or not isinstance(resource, bytes):
        raise ValueError("Missing AU-08 definition identifier or deployed resource bytes")
    source, = SOURCES["bpmn"]
    if source != RESOURCES + RESOURCE:
        raise ValueError("AU-08 inventory no longer names the fixed workflow resource")
    expected = digest(root.resolve(), source)
    if sha256(resource).hexdigest() != expected:
        raise ValueError("AU-08 deployed workflow differs from the reviewed source")
    return tenant, {"definition-id": definition_id, "resource-sha256": expected}


def read_workflow_pin(root, connection, owned):
    """Read the engine's stored definition and bytes within the owned fixture scope."""
    with fixture_transaction(connection, owned) as cursor:
        cursor.execute("""
            select definition.tenant_id_, definition.id_, definition.key_,
                   definition.version_, definition.suspension_state_,
                   definition.resource_name_, resource.bytes_
            from public.act_re_procdef definition
            left join public.act_ge_bytearray resource
              on resource.deployment_id_ = definition.deployment_id_
             and resource.name_ = definition.resource_name_
            where definition.tenant_id_ = %s and definition.key_ = %s
            limit 2
            """, (synthetic_tenant_id(owned.instance), DEFINITION_KEY))
        definitions = [
            {"tenant": tenant, "definition_id": definition_id, "key": key,
             "version": version, "active": suspension_state == 1,
             "resource_name": name,
             "resource": resource.tobytes() if isinstance(resource, memoryview) else resource}
            for tenant, definition_id, key, version, suspension_state, name, resource in cursor.fetchall()
        ]
        return check_workflow_pin(root, owned.instance, definitions)
