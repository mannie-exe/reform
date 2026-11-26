package design.inherent.reform.reference;

/** Read-only, already admitted context data. No IO, chunk requests or compiler work on lookup. */
public interface ReferenceResolver<K, V> {
  ReferenceStamp stamp();

  ReferenceResolution<V> resolve(K key);

  ReferencePage<K> search(ReferenceQuery query);

  /** Existing resolvers keep text-search semantics; additional matching is explicitly supported. */
  default boolean supports(ReferenceQuery.Match match) {
    return match == ReferenceQuery.Match.TEXT;
  }
}
