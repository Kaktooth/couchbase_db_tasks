package org.kaktooth;

import static com.couchbase.client.java.kv.MutateInSpec.upsert;
import static com.couchbase.client.java.kv.UpsertOptions.upsertOptions;
import static com.couchbase.client.java.transactions.config.TransactionOptions.transactionOptions;

import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.DocumentExistsException;
import com.couchbase.client.core.msg.kv.DurabilityLevel;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.MutationResult;
import java.time.Duration;
import java.util.List;
import java.util.Random;

public class GameConcurrentServerStart {

  static String connectionString = "couchbases://cb.xuuz889eilulibtj.cloud.couchbase.com";
  static String username = "test-user";
  static String password = "(passPass-123)";
  static String bucketName = "game_server_db";

  public static void main(String... args) {
    Cluster cluster = Cluster.connect(
        connectionString,
        ClusterOptions.clusterOptions(username, password).environment(env -> {
          env.applyProfile("wan-development");
        })
    );
    var span = cluster.environment().requestTracer().requestSpan("span", null);

    Bucket bucket = cluster.bucket(bucketName);
    bucket.waitUntilReady(Duration.ofSeconds(10));

    Scope scope = bucket.scope("game_scope_1");
    Collection collection = scope.collection("game_db_collection");

    JsonObject playerContent = JsonObject.create()
        .put("player-name", "player1")
        .put("health", 100)
        .put("damage", 5)
        .put("defence", 4);

    JsonObject monsterContent = JsonObject.create()
        .put("monster-id", 198942)
        .put("health", 132)
        .put("damage", 6)
        .put("defence", 2);

    MutationResult docInsertingPlayersResult = collection.upsert("game_players", playerContent,
        upsertOptions().durability(DurabilityLevel.MAJORITY));
    MutationResult docInsertingMonstersResult = collection.upsert("game_monsters", monsterContent,
        upsertOptions().durability(DurabilityLevel.MAJORITY));

    //attack randomly
    new Thread(() -> {
      Integer actions = 15;
      while (actions > 0) {
        Random rand = new Random();
        Integer action = rand.nextInt(100);
        if (action < 54) {
          cluster.transactions().run((ctx) -> {
                GetResult playersResult = collection.get("game_players");

                JsonObject playersContent = playersResult.contentAsObject();

                GetResult monstersResult = collection.get("game_monsters");
                JsonObject monstersContent = monstersResult.contentAsObject();
                System.out.println(
                    "[Action 54] Current Monster Health: " + monstersContent.get("health"));

                Integer playerAttackAction = rand.nextInt(6);
                if (playerAttackAction == 1) {
                  Integer playerOverallDamage = playerContent.getInt("damage")
                      - monstersContent.getInt("defence");
                  Integer monsterHealth = monstersContent.getInt("health") - playerOverallDamage;
                  Integer decreasedPlayerHealth = playersContent.getInt("health") - 1;
                  if (monsterHealth <= 0) {
                    collection.mutateIn("game_monsters", List.of(upsert("health", 0)));
                  } else {
                    collection.mutateIn("game_players",
                        List.of(upsert("health", decreasedPlayerHealth)));
                    collection.mutateIn("game_monsters", List.of(upsert("health", monsterHealth)));
                  }
                  System.out.println("Player attacked!");
                }
          }, transactionOptions().parentSpan(span));
          actions--;
        }
      }
    }).start();

    new Thread(() -> {
      Integer actions = 15;
      while (actions > 0) {
        Random rand = new Random();
        Integer action = rand.nextInt(100);
        if (action > 54) {
          cluster.transactions().run((ctx) -> {
          GetResult playersResult = collection.get("game_players");
          JsonObject playersContent = playersResult.contentAsObject();
          System.out.println("[Action 54] Current Player Health: " + playersContent.get("health"));

          GetResult monstersResult = collection.get("game_monsters");
          JsonObject monstersContent = monstersResult.contentAsObject();

          Integer monsterAttackAction = rand.nextInt(3);
          if (monsterAttackAction == 1) {
            Integer monsterOverallDamage = monstersContent.getInt("damage")
                - playersContent.getInt("defence");
            Integer playerHealth = playersContent.getInt("health") - monsterOverallDamage;
            if (playerHealth <= 0) {
              collection.mutateIn("game_players", List.of(upsert("health", 0)));
            } else {
              collection.mutateIn("game_players", List.of(upsert("health", playerHealth)));
            }
            System.out.println("Monster attacked!");
          }
          }, transactionOptions().parentSpan(span));
          actions--;
        }
      }
    }).start();
  }
}