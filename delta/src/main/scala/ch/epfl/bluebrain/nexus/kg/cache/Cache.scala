package ch.epfl.bluebrain.nexus.kg.cache

import java.util.concurrent.ConcurrentHashMap

import cats.Monad
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStore.Subscription
import ch.epfl.bluebrain.nexus.commons.cache._

abstract class Cache[F[_]: Monad, K, V](private[cache] val store: KeyValueStore[F, K, V]) {

  /**
    * Adds a subscription to the cache
    *
    * @param value the method that gets triggered when a change to key value store occurs
    */
  def subscribe(value: OnKeyValueStoreChange[F, K, V]): F[KeyValueStore.Subscription] = store.subscribe(value)

  /**
    * Removes a subscription from the cache
    *
    * @param subscription the subscription to be removed
    */
  def unsubscribe(subscription: Subscription): F[Unit] = store.unsubscribe(subscription)

  private[cache] def get(id: K): F[Option[V]] = store.get(id)

  private[cache] def replace(id: K, value: V): F[Unit] = store.put(id, value)
}

object Cache {

  implicit private[cache] class ConcurrentHashMapSyntax[K, V](private val map: ConcurrentHashMap[K, V]) extends AnyVal {
    def getSafe(key: K): Option[V] = Option(map.get(key))
    def putAndReturn(key: K, value: V): V = {
      map.put(key, value)
      value
    }
  }
}
