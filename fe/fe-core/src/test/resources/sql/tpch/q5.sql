[sql]
select
    n_name,
    sum(l_extendedprice * (1 - l_discount)) as revenue
from
    customer,
    orders,
    lineitem,
    supplier,
    nation,
    region
where
        c_custkey = o_custkey
  and l_orderkey = o_orderkey
  and l_suppkey = s_suppkey
  and c_nationkey = s_nationkey
  and s_nationkey = n_nationkey
  and n_regionkey = r_regionkey
  and r_name = 'AFRICA'
  and o_orderdate >= date '1995-01-01'
  and o_orderdate < date '1996-01-01'
group by
    n_name
order by
    revenue desc ;
[result]
TOP-N (order by [[55: sum DESC NULLS LAST]])
    TOP-N (order by [[55: sum DESC NULLS LAST]])
        AGGREGATE ([GLOBAL] aggregate [{55: sum=sum(55: sum)}] group by [[46: N_NAME]] having [null]
            EXCHANGE SHUFFLE[46]
                AGGREGATE ([LOCAL] aggregate [{55: sum=sum(54: expr)}] group by [[46: N_NAME]] having [null]
                    INNER JOIN (join-predicate [50: R_REGIONKEY = 47: N_REGIONKEY] post-join-predicate [null])
                        SCAN (columns[50: R_REGIONKEY, 51: R_NAME] predicate[51: R_NAME = AFRICA])
                        EXCHANGE BROADCAST
                            INNER JOIN (join-predicate [45: N_NATIONKEY = 40: S_NATIONKEY] post-join-predicate [null])
                                SCAN (columns[45: N_NATIONKEY, 46: N_NAME, 47: N_REGIONKEY] predicate[null])
                                EXCHANGE BROADCAST
                                    INNER JOIN (join-predicate [37: S_SUPPKEY = 22: L_SUPPKEY AND 40: S_NATIONKEY = 4: C_NATIONKEY] post-join-predicate [null])
                                        SCAN (columns[37: S_SUPPKEY, 40: S_NATIONKEY] predicate[null])
                                        EXCHANGE SHUFFLE[22]
                                            INNER JOIN (join-predicate [1: C_CUSTKEY = 11: O_CUSTKEY] post-join-predicate [null])
                                                SCAN (columns[1: C_CUSTKEY, 4: C_NATIONKEY] predicate[null])
                                                EXCHANGE SHUFFLE[11]
                                                    INNER JOIN (join-predicate [20: L_ORDERKEY = 10: O_ORDERKEY] post-join-predicate [null])
                                                        SCAN (columns[20: L_ORDERKEY, 22: L_SUPPKEY, 25: L_EXTENDEDPRICE, 26: L_DISCOUNT] predicate[null])
                                                        EXCHANGE SHUFFLE[10]
                                                            SCAN (columns[10: O_ORDERKEY, 11: O_CUSTKEY, 14: O_ORDERDATE] predicate[14: O_ORDERDATE >= 1995-01-01 AND 14: O_ORDERDATE < 1996-01-01])
[end]

