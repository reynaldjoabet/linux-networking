# Azure Cosmos DB: Request Units (RU)

## What is an RU?

RU = **Request Unit**. It is the currency for database operations: a normalized unit of database work that represents:

- CPU
- Memory
- Disk IO
- Network
- Index maintenance
- Replication overhead

1 RU = a fixed amount of "work" the database promises to perform within a given latency.

1 RU = the cost of a **point read** of a 1 KB item (a point read fetches a single item by its ID and partition key).

Costs add up linearly: if a write takes 10 RU and you do that write 10 times, it costs 100 RU.

## Billing

Two components make up an Azure Cosmos DB bill:

- Provisioned throughput
- Consumed storage

## Autoscale and throttling

You set a maximum limit (e.g. 4,000 RU/s), and Azure scales instantly between 10% of that maximum and the maximum itself based on traffic.

In practice, with a 4,000 RU/s max limit:

- **The range:** the database can scale anywhere between 400 RU/s and 4,000 RU/s.
- **Idle time:** if no one is using your app at 3:00 AM, the system drops to 400 RU/s. You are billed for 400 RU/s for that hour.

If your application tries to consume more RUs than you have provisioned in a given second, Cosmos DB **throttles** the request. It returns HTTP `429 Too Many Requests`, telling your application to wait and try again.

## Document size drives cost

Best practice for NoSQL databases is to keep JSON documents small — 100 KB, for example, is already large. With really large documents, the cost of writes is much higher.

| Operation           | Approximate cost                                        |
| ------------------- | ------------------------------------------------------- |
| 1 KB write          | ~5 RU                                                   |
| 100 KB write        | ~50–80 RU (depends on the number of indexed properties) |
| 10 writes of 100 KB | ~800 RU                                                 |

## Why more indexes = more RU

When you write a document, the database doesn't just "save" the file. It scans the JSON, finds every property, and updates a hidden lookup table (the index) for each one.

If a 100 KB document has 50 different fields and indexing is set to "All" (the default in Cosmos DB and others):

1. The DB writes the document to the physical disk (**base cost**).
2. The DB then performs 50 separate tiny writes to the index tree to record where each value lives (**index overhead**).

The math of RU costs:

```text
TotalWriteCost = StorageCost + ∑(IndividualIndexUpdates)
```

Every indexed field must be updated on a write.

## Sharding vs replication

- Each shard is itself a replica set.
- Replication keeps your data safe; sharding makes your database scalable and cheaper per operation.
- **Citus** brings true sharding to Postgres: it turns Postgres into a distributed database.
