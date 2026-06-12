package com.hetzner.hackathon.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

data class ServerType(
    val name: String, 
    val description: String, 
    val cores: Int, 
    val memory: Float,
    val prices: List<Price>? = null
) {
    data class Price(val location: String, val priceHourly: JsonObject, val priceMonthly: JsonObject)
}

data class Location(val name: String, val description: String, val city: String)
data class CreatedServer(val id: Long, val ip: String)
data class Network(val id: Long, val name: String, val ipRange: String, val serverIds: List<Long>)
data class NetworkInfo(val networkId: Long, val privateIp: String)
data class SshKey(val id: Long, val name: String)
data class Image(val id: Long, val name: String?, val description: String)

class HCloudClient(private val token: String) {
    private val client = HttpClient.newBuilder().build()
    private val gson = Gson()
    private val baseUrl = "https://api.hetzner.cloud/v1"

    fun getServerTypes(location: String? = null): CompletableFuture<List<ServerType>> {
        var url = "$baseUrl/server_types"
        if (location != null) {
            // In HCloud API, server_types response includes "prices" which contains location info
            // or we can filter by querying, but typically we fetch all and filter client side 
            // for "available at" if the API doesn't support a direct filter parameter for availability.
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) {
                    throw Exception("API Error (${response.statusCode()}): ${response.body()}")
                }
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val typesJson = json.getAsJsonArray("server_types") ?: throw Exception("Invalid API response: missing 'server_types'")
                
                val result = mutableListOf<ServerType>()
                typesJson.forEach {
                    val obj = it.asJsonObject
                    
                    // Filter by location if specified
                    // Note: 'prices' in server_type object lists locations where it's available
                    val prices = obj.getAsJsonArray("prices")
                    val availableLocations = prices?.map { p -> p.asJsonObject.get("location").asString } ?: emptyList()
                    
                    if (location == null || availableLocations.contains(location)) {
                        result.add(ServerType(
                            obj.get("name").asString,
                            obj.get("description").asString,
                            obj.get("cores").asInt,
                            obj.get("memory").asFloat,
                            prices?.map { p -> 
                                val pObj = p.asJsonObject
                                ServerType.Price(
                                    pObj.get("location").asString,
                                    pObj.getAsJsonObject("price_hourly"),
                                    pObj.getAsJsonObject("price_monthly")
                                )
                            }
                        ))
                    }
                }
                result
            }
    }

    fun getLocations(): CompletableFuture<List<Location>> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/locations"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) {
                    throw Exception("API Error (${response.statusCode()}): ${response.body()}")
                }
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val locs = json.getAsJsonArray("locations") ?: throw Exception("Invalid API response: missing 'locations'")
                locs.map {
                    val obj = it.asJsonObject
                    Location(
                        obj.get("name").asString,
                        obj.get("description").asString,
                        obj.get("city").asString
                    )
                }
            }
    }

    fun createServer(name: String, serverType: String, location: String, userData: String? = null, sshKeys: List<String> = emptyList(), image: String = "ubuntu-26.04"): CompletableFuture<CreatedServer> {
        val body = mutableMapOf(
            "name" to name,
            "server_type" to serverType,
            "location" to location,
            "image" to image,
            "public_net" to mapOf("enable_ipv4" to true, "enable_ipv6" to true)
        )
        if (userData != null) {
            body["user_data"] = userData
        }
        if (sshKeys.isNotEmpty()) {
            body["ssh_keys"] = sshKeys
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/servers"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                if (response.statusCode() != 201) {
                   throw Exception("Failed to create server: ${response.body()}")
                }
                val server = json.getAsJsonObject("server")
                val id = server.get("id").asLong
                val ip = server.getAsJsonObject("public_net").getAsJsonObject("ipv4").get("ip").asString
                CreatedServer(id, ip)
            }
    }

    fun getImages(labelSelector: String? = null): CompletableFuture<List<Image>> {
        var url = "$baseUrl/images?type=snapshot"
        if (labelSelector != null) {
            url += "&label_selector=$labelSelector"
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) throw Exception("API Error: ${response.body()}")
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                json.getAsJsonArray("images").map {
                    val obj = it.asJsonObject
                    Image(
                        obj.get("id").asLong,
                        if (obj.get("name").isJsonNull) null else obj.get("name").asString,
                        obj.get("description").asString
                    )
                }
            }
    }

    fun getSshKeys(): CompletableFuture<List<SshKey>> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/ssh_keys"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) throw Exception("API Error: ${response.body()}")
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                json.getAsJsonArray("ssh_keys").map {
                    val obj = it.asJsonObject
                    SshKey(obj.get("id").asLong, obj.get("name").asString)
                }
            }
    }

    fun getServerStatus(serverId: Long): CompletableFuture<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/servers/$serverId"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) {
                    throw Exception("API Error (${response.statusCode()}): ${response.body()}")
                }
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val server = json.getAsJsonObject("server") ?: throw Exception("Invalid API response: missing 'server'")
                server.get("status").asString
            }
    }

    fun deleteServer(serverId: Long): CompletableFuture<Void?> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/servers/$serverId"))
            .header("Authorization", "Bearer $token")
            .DELETE()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) {
                    throw Exception("Failed to delete server: ${response.body()}")
                }
                null
            }
    }

    fun getNetworks(): CompletableFuture<List<Network>> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/networks"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) throw Exception("API Error: ${response.body()}")
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                json.getAsJsonArray("networks").map {
                    val obj = it.asJsonObject
                    Network(
                        obj.get("id").asLong, 
                        obj.get("name").asString, 
                        obj.get("ip_range").asString,
                        obj.getAsJsonArray("servers").map { s -> s.asLong }
                    )
                }
            }
    }

    fun createNetwork(name: String, ipRange: String): CompletableFuture<Network> {
        val body = mapOf("name" to name, "ip_range" to ipRange)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/networks"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 201) throw Exception("Failed to create network: ${response.body()}")
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val net = json.getAsJsonObject("network")
                Network(
                    net.get("id").asLong, 
                    net.get("name").asString, 
                    net.get("ip_range").asString,
                    net.getAsJsonArray("servers").map { s -> s.asLong }
                )
            }
    }

    fun deleteNetwork(networkId: Long): CompletableFuture<Void?> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/networks/$networkId"))
            .header("Authorization", "Bearer $token")
            .DELETE()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 204 && response.statusCode() != 200) {
                    throw Exception("Failed to delete network: ${response.body()}")
                }
                null
            }
    }

    fun addSubnet(networkId: Long, ipRange: String): CompletableFuture<Void?> {
        val body = mapOf("type" to "cloud", "network_zone" to "eu-central", "ip_range" to ipRange)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/networks/$networkId/actions/add_subnet"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 201) throw Exception("Failed to add subnet: ${response.body()}")
                null
            }
    }

    fun attachServerToNetwork(serverId: Long, networkId: Long): CompletableFuture<Void?> {
        val body = mapOf("network" to networkId)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/servers/$serverId/actions/attach_to_network"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 201) throw Exception("Failed to attach server: ${response.body()}")
                null
            }
    }

    fun detachServerFromNetwork(serverId: Long, networkId: Long): CompletableFuture<Void?> {
        val body = mapOf("network" to networkId)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/servers/$serverId/actions/detach_from_network"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 201) throw Exception("Failed to detach server: ${response.body()}")
                null
            }
    }

    fun getServerNetworkInfo(serverId: Long): CompletableFuture<List<NetworkInfo>> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/servers/$serverId"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() != 200) throw Exception("Failed to get server info: ${response.body()}")
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val server = json.getAsJsonObject("server")
                val privateNets = server.getAsJsonArray("private_net")
                privateNets.map {
                    val obj = it.asJsonObject
                    NetworkInfo(obj.get("network").asLong, obj.get("ip").asString)
                }
            }
    }
}
