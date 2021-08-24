{{ config(schema="test_normalization", tags=["nested-intermediate"]) }}
-- SQL model to cast each column to its adequate SQL type converted from the JSON schema type
select
    {{ QUOTE('_AIRBYTE_CHILDREN_HASHID') }},
    cast(owner_id as {{ dbt_utils.type_bigint() }}) as owner_id,
    airbyte_emitted_at
from {{ ref('unnest_alias_children_owner_ab1') }}
-- owner at unnest_alias/children/owner

