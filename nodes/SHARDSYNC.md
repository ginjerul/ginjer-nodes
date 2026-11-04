# Shard sync (work in progress)

Shares the Nodes state (residents, towns, nations) between Reef shards through a common folder.

**Status: publish only.** Each shard merges its changes into the shared copy. Changes made on other
shards are counted but not yet applied to the live objects.

Enable on every shard in `plugins/nodes/config.yml`:

```yaml
shardSync:
  enabled: true
  folder: "/path/to/folder/all/shards/share"   # e.g. next to shared-playerdata
  intervalSeconds: 3
```

Then run `/nda shardsync` to see the store version, how often this shard published, how many changes
from other shards are waiting, conflicts and broken references.

Tests (no server needed): `nodes/tools/shardsync-test/run.sh`.
