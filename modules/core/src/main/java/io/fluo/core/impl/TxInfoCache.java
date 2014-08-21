/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.core.impl;

import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;

import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;

public class TxInfoCache {

  final static int CACHE_TIMEOUT_MIN = 24 * 60;

  private static class TxStatusWeigher implements Weigher<PrimaryRowColumn,TxInfo> {
    @Override
    public int weigh(PrimaryRowColumn key, TxInfo value) {
      return key.weight() + (value.lockValue == null ? 0 : value.lockValue.length) + 24;
    }
  }

  private Cache<PrimaryRowColumn,TxInfo> cache;
  private Environment env;

  TxInfoCache(Environment env) {
    cache = CacheBuilder.newBuilder().expireAfterAccess(CACHE_TIMEOUT_MIN, TimeUnit.MINUTES).maximumWeight(10000000).weigher(new TxStatusWeigher())
        .concurrencyLevel(10).build();
    this.env = env;
  }

  public TxInfo getTransactionInfo(final Bytes prow, final Column pcol, final long startTs) {
    return getTransactionInfo(new PrimaryRowColumn(prow, pcol, startTs));
  }

  public TxInfo getTransactionInfo(PrimaryRowColumn key) {

    TxInfo txInfo = cache.getIfPresent(key);
    if (txInfo == null) {
      txInfo = TxInfo.getTransactionInfo(env, key.prow, key.pcol, key.startTs);
      if (txInfo.status == TxStatus.ROLLED_BACK || txInfo.status == TxStatus.COMMITTED) {
        // only cache for these statuses which are not expected to change, other status can change over time
        cache.put(key, txInfo);
      }
    }

    return txInfo;
  }
}
