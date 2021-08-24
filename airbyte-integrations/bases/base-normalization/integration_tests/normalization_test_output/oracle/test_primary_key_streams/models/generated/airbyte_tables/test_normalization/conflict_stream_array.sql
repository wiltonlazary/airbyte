{{ config(schema="test_normalization", tags=["top-level"]) }}
-- Final base SQL model
select
    id,
    conflict_stream_array,
    airbyte_emitted_at,
    {{ QUOTE('_AIRBYTE_CONFLICT_STREAM_ARRAY_HASHID') }}
from {{ ref('conflict_stream_array_ab3') }}
-- conflict_stream_array from {{ source('test_normalization', 'airbyte_raw_conflict_stream_array') }}

