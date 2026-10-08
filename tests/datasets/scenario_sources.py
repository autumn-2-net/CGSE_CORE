"""Identify historical request inputs without treating solver reports as fixtures."""

GLOBAL_CATALOG = 'miracle-cycle-20261002/audit-original-catalog.json'


def records(value, container):
    if container != '$':
        value = value[container]
    return value if isinstance(value, list) else [value]


def is_scenario(value):
    return (isinstance(value, dict) and isinstance(value.get('target'), str)
            and ('requests' in value or 'amount' in value)
            and not any(key in value for key in ('recipes', 'result', 'request', 'feasible', 'maxfast', 'cgse',
                                                'scip', 'tail', 'runtime_patterns', 'elapsed_ms', 'peak_reserved_bytes')))


def classify(value):
    container = '$'
    if isinstance(value, dict) and not is_scenario(value):
        container = next((key for key in ('cases', 'groups') if isinstance(value.get(key), list)), None)
        if not container:
            return None
    rows = records(value, container)
    groups = [row for row in rows if is_scenario(row)]
    if not groups:
        return None
    return {'container': container, 'groups': len(groups), 'rows': len(rows),
            'requests': sum(len(g.get('requests', [g])) for g in groups),
            'embedded_catalog_groups': sum('catalog' in g for g in groups),
            'external_catalog_groups': sum('catalog' not in g for g in groups),
            'fields': sorted(set().union(*(g.keys() for g in groups))),
            'has_native_orders': isinstance(value, dict) and 'orders' in value}


def provenance(source, descriptor):
    """Only return a global catalog when a preserved source driver proves the association."""
    if descriptor['has_native_orders']:
        return {'catalog_policy': 'native AE provider-order capture; original native driver required',
                'global_catalog': None,
                'evidence': [source.split('/')[0] + '/RandomOrderAudit.java: live provider-order replay']}
    if not descriptor['external_catalog_groups']:
        return {'catalog_policy': 'each group.catalog; no global catalog substitution', 'global_catalog': None,
                'evidence': ['quantity-regression-20261002/QuantitySweep.java: main selects group.catalog when present']}
    campaign = source.split('/')[0]
    evidence = {
        'linear-search-20261002': ['pruned-search-20261002/run_regression.py: jobs and QuantitySweep catalog argument'],
        'pruned-search-20261002': ['pruned-search-20261002/run_archived.py: QuantitySweep catalog argument'],
        'quantity-regression-20261002': ['quantity-regression-20261002/prepare.py: SOURCE and catalog',
                                       'quantity-regression-20261002/QuantitySweep.java: catalog override and stock precedence'],
        'miracle-cycle-20261002': ['miracle-cycle-20261002/next_audit_cases.py: copies server/local/ae-audit/catalog.json to audit-original-catalog.json',
                                  'miracle-cycle-20261002/NetworkAudit.java: prepare captures catalog, run uses per-case stock or allStock'],
    }
    if campaign in evidence:
        return {'catalog_policy': 'group.catalog when present; otherwise preserved original audit catalog',
                'global_catalog': GLOBAL_CATALOG, 'evidence': evidence[campaign]}
    return {'catalog_policy': 'unresolved; replay refuses to guess a catalog', 'global_catalog': None, 'evidence': []}
