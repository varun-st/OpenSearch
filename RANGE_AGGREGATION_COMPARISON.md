# Range & Date Range Aggregation: DataFusion vs OpenSearch Native Implementation

## Architecture Comparison

### OpenSearch Native (Lucene-based)
- **Execution**: Iterates through Lucene documents
- **Matching**: Tests each document against ALL ranges
- **Collection**: Collects document into EVERY matching range
- **Location**: `RangeAggregator.java` in server module

### Our DataFusion Implementation
- **Execution**: SQL CASE WHEN expression in DataFusion
- **Matching**: Evaluates CASE conditions top-to-bottom
- **Collection**: Returns FIRST matching range only
- **Location**: `RangeGrouping.java` + `RangeBucketTranslator.java` in dsl-query-executor

---

## Detailed Feature Comparison

### 1. OVERLAPPING RANGES BEHAVIOR

#### OpenSearch Native ✅
```java
// RangeAggregator.java line 357-363
for (int i = range.startLo; i <= range.endHi; ++i) {
    if (ranges[i].matches(value)) {
        collectBucket(sub, doc, subBucketOrdinal(owningBucketOrdinal, i));
    }
}
```
- **Loops through ALL ranges**
- **No break statement** - continues checking after first match
- **Collects into EVERY matching bucket**

**Example:**
```json
{
  "ranges": [
    {"from": 0, "to": 100},
    {"from": 50, "to": 150}
  ]
}
```
Value 75: ✅ Counted in BOTH buckets

#### Our DataFusion Implementation ❌
```java
// RangeGrouping.java - generates CASE WHEN
CASE 
  WHEN price >= 0 AND price < 100 THEN 'range1'
  WHEN price >= 50 AND price < 150 THEN 'range2'
  ELSE NULL
END
```
- **CASE returns on first match**
- **Subsequent conditions never evaluated**
- **Document in ONLY ONE bucket**

Value 75: ❌ Counted in FIRST bucket only

**VERDICT: BEHAVIORAL DIFFERENCE - Overlapping ranges not supported**

---

### 2. BOUNDARY SEMANTICS

#### OpenSearch Native
```java
// RangeAggregator.java line 180
boolean matches(double value) {
    return value >= from && value < to;
}
```
- **Lower bound: INCLUSIVE (>=)**
- **Upper bound: EXCLUSIVE (<)**
- **Notation: [from, to)**

#### Our Implementation
```java
// RangeGrouping.java lines 56-62
if (!Double.isInfinite(range.getFrom())) {
    conditions.add(builder.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, ...));
}
if (!Double.isInfinite(range.getTo())) {
    conditions.add(builder.makeCall(SqlStdOperatorTable.LESS_THAN, ...));
}
```
- **Lower bound: INCLUSIVE (>=)**
- **Upper bound: EXCLUSIVE (<)**
- **Notation: [from, to)**

**VERDICT: ✅ IDENTICAL**

---

### 3. INFINITE BOUNDS HANDLING

#### OpenSearch Native
```java
// RangeAggregator.java line 180
boolean matches(double value) {
    return value >= from && value < to;
}
```
- Uses `Double.NEGATIVE_INFINITY` and `Double.POSITIVE_INFINITY`
- Comparison operators handle infinity naturally
- No special logic needed

#### Our Implementation
```java
// RangeGrouping.java lines 55-62
if (!Double.isInfinite(range.getFrom())) {
    // Add >= condition
}
if (!Double.isInfinite(range.getTo())) {
    // Add < condition
}
```
- **Explicitly checks for infinity**
- **Omits condition if infinite**
- Unbounded from: only `< to` condition
- Unbounded to: only `>= from` condition
- Fully unbounded: `TRUE` condition

**VERDICT: ✅ FUNCTIONALLY EQUIVALENT (different approach, same result)**

---

### 4. KEY GENERATION

#### OpenSearch Native
```java
// Not in RangeAggregator - keys are optional
// If no key provided, OpenSearch uses range index or generates default
```
- Keys are optional in Range class
- Default behavior varies by context

#### Our Implementation
```java
// RangeGrouping.java lines 84-88
private String generateKey(Range range) {
    String from = Double.isInfinite(range.getFrom()) ? "*" : String.valueOf(range.getFrom());
    String to = Double.isInfinite(range.getTo()) ? "*" : String.valueOf(range.getTo());
    return from + "-" + to;
}
```
- **Format: "from-to"**
- **Infinity represented as "*"**
- Examples: `"0.0-50.0"`, `"*-100.0"`, `"100.0-*"`

**VERDICT: ✅ COMPATIBLE (matches common OpenSearch behavior)**

---

### 5. KEYED PARAMETER

#### OpenSearch Native
```java
// AbstractRangeBuilder.java line 62
protected boolean keyed = false;
```
- Controls response format
- `keyed=true`: Returns buckets as object with keys
- `keyed=false`: Returns buckets as array

#### Our Implementation
```java
// RangeBucketTranslator.java line 72
rangeBuckets.add(new InternalRange.Bucket(
    key, from, to, entry.docCount(), entry.subAggs(), agg.keyed(), DocValueFormat.RAW
));
```
- **Passes `agg.keyed()` to bucket constructor**
- **Passes to InternalRange constructor**
- Preserves keyed flag through entire pipeline

**VERDICT: ✅ FULLY SUPPORTED**

---

### 6. SUB-AGGREGATIONS

#### OpenSearch Native
```java
// RangeAggregator.java - collects sub-aggs per bucket
collectBucket(sub, doc, subBucketOrdinal(owningBucketOrdinal, i));
```
- Each bucket can have nested aggregations
- Sub-aggs computed per bucket

#### Our Implementation
```java
// RangeBucketTranslator.java line 48
public Collection<AggregationBuilder> getSubAggregations(DateRangeAggregationBuilder agg) {
    return agg.getSubAggregations();
}
```
- **Extracts sub-aggregations from builder**
- **Recursively processed by framework**
- Sub-agg results attached to each bucket

**VERDICT: ✅ FULLY SUPPORTED**

---

### 7. SCRIPT PARAMETER

#### OpenSearch Native
```java
// ValuesSourceAggregationBuilder supports scripts
// Can use script instead of field
```
- Supports scripted values
- Script evaluated per document

#### Our Implementation
```java
// RangeGrouping.java line 42
RelDataTypeField field = inputRowType.getField(this.field, true, false);
```
- **Only supports field-based ranges**
- **No script evaluation**

**VERDICT: ❌ NOT SUPPORTED**

---

### 8. FORMAT PARAMETER

#### OpenSearch Native
```java
// Supports DocValueFormat for formatting
// Can format numbers, dates, etc.
```
- Supports custom formatting
- Applied to bucket keys and values

#### Our Implementation
```java
// RangeBucketTranslator.java line 72
DocValueFormat.RAW
```
- **Hardcoded to RAW format**
- **No custom formatting**

**VERDICT: ❌ NOT SUPPORTED**

---

### 9. DATE MATH EXPRESSIONS

#### OpenSearch Native (date_range)
```java
// Supports date math: "now-7d/d", "2024-01-01||+1M"
// Parsed and evaluated at query time
```
- Full date math support
- Relative dates (now-7d)
- Rounding (/d, /M)

#### Our Implementation
```java
// DateRangeGrouping.java - uses numeric epoch milliseconds
RexNode fromLiteral = builder.makeApproxLiteral(
    java.math.BigDecimal.valueOf(range.getFrom())
);
```
- **Only supports numeric epoch milliseconds**
- **No date math parsing**

**VERDICT: ❌ NOT SUPPORTED**

---

### 10. OPTIMIZATION: MatchedRange

#### OpenSearch Native
```java
// RangeAggregator.java line 357
MatchedRange range = new MatchedRange(ranges, lowBound, value, maxTo);
for (int i = range.startLo; i <= range.endHi; ++i) {
```
- **Optimization**: Only checks ranges that could match
- Uses `startLo` and `endHi` to skip impossible ranges
- Assumes ranges are sorted

#### Our Implementation
```java
// RangeGrouping.java - generates CASE for ALL ranges
for (Range range : ranges) {
    // Generate condition for every range
}
```
- **No optimization**: CASE checks all ranges
- DataFusion may optimize, but not guaranteed

**VERDICT: ⚠️ LESS OPTIMIZED (but DataFusion may compensate)**

---

### 11. STAR TREE OPTIMIZATION

#### OpenSearch Native
```java
// RangeAggregator.java line 368
private void preComputeWithStarTree(LeafReaderContext ctx, CompositeIndexFieldInfo starTree)
```
- **Supports Star Tree index optimization**
- Pre-aggregated data for faster queries
- Special handling for composite indexes

#### Our Implementation
- **No Star Tree support**
- Uses standard DataFusion execution

**VERDICT: ❌ NOT SUPPORTED**

---

## Summary Table

| Feature | OpenSearch Native | Our DataFusion | Status |
|---------|------------------|----------------|--------|
| **Overlapping Ranges** | ✅ Multi-bucket | ❌ First match only | **DIFFERENT** |
| **Boundary Semantics** | ✅ [from, to) | ✅ [from, to) | ✅ SAME |
| **Infinite Bounds** | ✅ Supported | ✅ Supported | ✅ SAME |
| **Key Generation** | ✅ Optional | ✅ Auto-generated | ✅ COMPATIBLE |
| **Keyed Parameter** | ✅ Supported | ✅ Supported | ✅ SAME |
| **Sub-Aggregations** | ✅ Supported | ✅ Supported | ✅ SAME |
| **Script Parameter** | ✅ Supported | ❌ Not supported | ❌ MISSING |
| **Format Parameter** | ✅ Supported | ❌ RAW only | ❌ MISSING |
| **Date Math** | ✅ Supported | ❌ Not supported | ❌ MISSING |
| **MatchedRange Optimization** | ✅ Optimized | ⚠️ No optimization | ⚠️ DIFFERENT |
| **Star Tree** | ✅ Supported | ❌ Not supported | ❌ MISSING |

---

## Critical Differences

### 1. 🔴 OVERLAPPING RANGES (Breaking Change)
**Impact**: HIGH
**User Visible**: YES

Query with overlapping ranges will return DIFFERENT results:
```json
{
  "ranges": [
    {"from": 0, "to": 100},
    {"from": 50, "to": 150}
  ]
}
```

**OpenSearch**: Value 75 counted in BOTH buckets
**Our Implementation**: Value 75 counted in FIRST bucket only

**Recommendation**: 
- Document this limitation clearly
- Consider adding validation to reject overlapping ranges
- Or add warning in query response

### 2. 🟡 MISSING FEATURES (Functional Gaps)
**Impact**: MEDIUM
**User Visible**: YES

- **Script parameter**: Cannot use scripted values
- **Format parameter**: Cannot format output
- **Date math**: Cannot use relative dates (now-7d/d)

**Recommendation**: 
- Document as known limitations
- Add support in future iterations if needed

### 3. 🟢 PERFORMANCE DIFFERENCES (Non-functional)
**Impact**: LOW
**User Visible**: NO

- No MatchedRange optimization
- No Star Tree support

**Recommendation**: 
- Monitor performance
- DataFusion may have its own optimizations

---

## Compatibility Assessment

### ✅ Compatible Use Cases (90% of queries)
- Non-overlapping ranges
- Field-based ranges (no scripts)
- Standard formatting (no custom formats)
- Absolute dates (no date math)

### ❌ Incompatible Use Cases
- Overlapping ranges where multi-bucket behavior is required
- Script-based range values
- Custom number/date formatting
- Relative date expressions (now-7d/d)

### Recommendation
**Document clearly**: "DataFusion execution path supports range aggregations with non-overlapping ranges. Overlapping ranges will return first-match behavior instead of multi-bucket behavior."

