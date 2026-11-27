package com.zouhu.speedlimit.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.core.PoiInfo
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult
import com.baidu.mapapi.search.poi.OnGetPoiSearchResultListener
import com.baidu.mapapi.search.poi.PoiCitySearchOption
import com.baidu.mapapi.search.poi.PoiDetailResult
import com.baidu.mapapi.search.poi.PoiDetailSearchResult
import com.baidu.mapapi.search.poi.PoiIndoorResult
import com.baidu.mapapi.search.poi.PoiNearbySearchOption
import com.baidu.mapapi.search.poi.PoiResult
import com.baidu.mapapi.search.poi.PoiSearch
import com.baidu.mapapi.search.poi.PoiSortType
import com.baidu.mapapi.search.sug.OnGetSuggestionResultListener
import com.baidu.mapapi.search.sug.SuggestionResult
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.zouhu.speedlimit.data.map.baidu.CoordUtil
import com.zouhu.speedlimit.data.nav.BaiduNavSource
import com.zouhu.speedlimit.data.nav.NavTrace
import com.zouhu.speedlimit.data.search.SearchHistoryStore
import com.zouhu.speedlimit.data.search.SearchPlace
import com.zouhu.speedlimit.data.search.distanceMeters
import com.zouhu.speedlimit.data.search.SearchRanking
import com.zouhu.speedlimit.data.search.formatDistance
import com.zouhu.speedlimit.domain.model.NavPoint
import com.zouhu.speedlimit.ui.CruiseUiState
import com.zouhu.speedlimit.ui.screen.MapLayer
import com.zouhu.speedlimit.ui.theme.LocalSlmColors
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** One page is 20 POIs; nearby search is sorted by distance so page 0 is the near set. */
private const val POI_PAGE_SIZE = 20
private const val NEARBY_RADIUS_M = 5_000
/** Local hits first, then nationwide completions; enough to scroll without flooding. */
/** Safety net: publish whatever arrived if one API never calls back. */
private const val SEARCH_TIMEOUT_MS = 2_500L

/** Phase 1: search -> pick POI -> multi-route preview on our map -> start navigation. */
@Composable
fun NavSearchScreen(
    source: BaiduNavSource,
    cruise: CruiseUiState,
    onStartNav: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalSlmColors.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchPlace>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<SearchPlace?>(null) }
    var searchSeq by remember { mutableStateOf(0) }
    val disposed = remember { AtomicBoolean(false) }
    // System back belongs to the search layer while it is open; otherwise it collapses
    // the map underneath and leaves the preview visible (VA-F09).
    BackHandler { onBack() }
    val history = remember { SearchHistoryStore(context) }
    val searchHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var recent by remember { mutableStateOf(history.load()) }
    val navState by source.state.collectAsStateWithLifecycle()
    val tabs by source.routeTabs.collectAsStateWithLifecycle()
    val selectedTab by source.selectedRouteIndex.collectAsStateWithLifecycle()
    val navStatus by source.status.collectAsStateWithLifecycle()
    val progress = remember { Animatable(1f) }

    val geo = remember { GeoCoder.newInstance() }
    val poi = remember { PoiSearch.newInstance() }
    val suggestion = remember { SuggestionSearch.newInstance() }
    DisposableEffect(Unit) {
        onDispose {
            NavTrace.log("MAP", "search preview dispose")
            // Invalidate every async exit before destroying the SDK objects: a pending
            // 2.5 s timeout or a late result must not touch a destroyed searcher (VA-S01).
            disposed.set(true)
            searchSeq += 1
            searchHandler.removeCallbacksAndMessages(null)
            geo.destroy()
            poi.destroy()
            suggestion.destroy()
        }
    }

    fun toPlace(p: PoiInfo, center: LatLng?): SearchPlace? {
        val loc = p.location ?: return null
        val distance = when {
            p.distance > 0 -> p.distance
            center != null -> distanceMeters(center.latitude, center.longitude, loc.latitude, loc.longitude)
            else -> -1
        }
        return SearchPlace(
            name = p.name ?: "",
            uid = p.uid ?: "",
            lat = loc.latitude,
            lng = loc.longitude,
            // PoiInfo carries province/city/area/address; showing them is what lets two
            // same-named banks be told apart.
            address = p.address ?: "",
            city = p.city ?: "",
            area = p.area ?: "",
            distanceM = distance,
        )
    }

    fun publish(seq: Int, places: List<SearchPlace>) {
        if (disposed.get() || seq != searchSeq) return
        results = places
        status = if (places.isEmpty()) "无结果，换个关键词" else ""
    }

    fun keyOf(p: SearchPlace): String = p.uid.ifBlank { p.name + "@" + p.city }

    /**
     * City-scoped POI search. Only used when the merged pass found nothing at all.
     */
    fun runCity(q: String, inCity: String, center: LatLng?, seq: Int) {
        if (disposed.get() || seq != searchSeq) return
        if (inCity.isBlank()) {
            publish(seq, emptyList())
            return
        }
        poi.setOnGetPoiSearchResultListener(object : OnGetPoiSearchResultListener {
            override fun onGetPoiResult(result: PoiResult?) {
                if (seq != searchSeq) return
                val places = result?.allPoi.orEmpty().mapNotNull { toPlace(it, center) }
                NavTrace.log("SEARCH", "city q=$q city=$inCity n=${places.size}")
                publish(seq, SearchRanking.rank(q, places, emptyList()))
            }

            override fun onGetPoiDetailResult(result: PoiDetailResult?) = Unit
            override fun onGetPoiDetailResult(result: PoiDetailSearchResult?) = Unit
            override fun onGetPoiIndoorResult(result: PoiIndoorResult?) = Unit
        })
        val started = runCatching {
            poi.searchInCity(
                PoiCitySearchOption()
                    .city(inCity)
                    .keyword(q)
                    .pageNum(0)
                    .pageCapacity(POI_PAGE_SIZE)
                    .cityLimit(false),
            )
        }.getOrDefault(false)
        if (!started) publish(seq, emptyList())
    }

    /**
     * One search pass: local POIs **and** nationwide suggestions run in parallel and are
     * merged, instead of a chain that stops at the first non-empty answer.
     *
     * Why: `searchNearby` is what fixed small nearby places, but it also matches local
     * noise for a remote query — "上海外滩" in Nanjing hits 上海路 POIs — and the old chain
     * published that and stopped, so the nationwide suggestion call was never reached.
     * Running both removes that coupling: a remote destination is always in the merged
     * list regardless of what the local pass returns.
     */
    fun runSearch(q: String, center: LatLng?, inCity: String, seq: Int) {
        if (disposed.get() || seq != searchSeq) return
        val local = mutableListOf<SearchPlace>()
        val remote = mutableListOf<SearchPlace>()
        var pending = if (center != null) 2 else 1
        var settled = false

        fun finish() {
            if (disposed.get() || settled || seq != searchSeq) return
            settled = true
            val localList = local.distinctBy { keyOf(it) }
            val remoteList = remote.distinctBy { keyOf(it) }
            // Q-21/O04: relevance tier, then distance inside the tier. Cross-city exact
            // destinations stay first; nearby category hits are no longer pushed behind
            // a farther same-category result.
            val places = SearchRanking.rank(q, localList, remoteList)
            NavTrace.log(
                "SEARCH",
                "merge q=$q local=${localList.size} remote=${remoteList.size} total=${places.size}",
            )
            if (places.isNotEmpty()) {
                publish(seq, places)
            } else if (inCity.isNotBlank()) {
                runCity(q, inCity, center, seq)
            } else {
                publish(seq, emptyList())
            }
        }

        fun done() {
            if (disposed.get()) return
            pending -= 1
            if (pending <= 0) finish()
        }

        if (center != null) {
            poi.setOnGetPoiSearchResultListener(object : OnGetPoiSearchResultListener {
                override fun onGetPoiResult(result: PoiResult?) {
                    if (seq == searchSeq) {
                        local += result?.allPoi.orEmpty().mapNotNull { toPlace(it, center) }
                        NavTrace.log("SEARCH", "nearby q=$q n=${local.size}")
                    }
                    done()
                }

                override fun onGetPoiDetailResult(result: PoiDetailResult?) = Unit
                override fun onGetPoiDetailResult(result: PoiDetailSearchResult?) = Unit
                override fun onGetPoiIndoorResult(result: PoiIndoorResult?) = Unit
            })
            val started = runCatching {
                poi.searchNearby(
                    PoiNearbySearchOption()
                        .keyword(q)
                        .location(center)
                        .radius(NEARBY_RADIUS_M)
                        .sortType(PoiSortType.distance_from_near_to_far)
                        .pageNum(0)
                        .pageCapacity(POI_PAGE_SIZE),
                )
            }.getOrDefault(false)
            if (!started) {
                NavTrace.log("SEARCH", "nearby rejected q=$q")
                done()
            }
        }

        // Nationwide, always; `city` only biases ranking once `citylimit(false)`.
        suggestion.setOnGetSuggestionResultListener(object : OnGetSuggestionResultListener {
            override fun onGetSuggestionResult(result: SuggestionResult?) {
                if (seq == searchSeq) {
                    remote += result?.allSuggestions.orEmpty().mapNotNull { item ->
                        val point = item.pt ?: return@mapNotNull null
                        SearchPlace(
                            name = item.key ?: "",
                            uid = item.uid ?: "",
                            lat = point.latitude,
                            lng = point.longitude,
                            city = item.city ?: "",
                            area = item.district ?: "",
                            distanceM = center?.let {
                                distanceMeters(it.latitude, it.longitude, point.latitude, point.longitude)
                            } ?: -1,
                        )
                    }
                    NavTrace.log("SEARCH", "suggestion q=$q n=${remote.size}")
                }
                done()
            }
        })
        val started = runCatching {
            suggestion.requestSuggestion(
                SuggestionSearchOption()
                    .keyword(q)
                    .city("全国")
                    .citylimit(false),
            )
        }.getOrDefault(false)
        if (!started) {
            NavTrace.log("SEARCH", "suggestion rejected q=$q")
            done()
        }

        // Never let one silent API swallow the whole search.
        searchHandler.postDelayed({ if (seq == searchSeq) finish() }, SEARCH_TIMEOUT_MS)
    }

    fun pick(place: SearchPlace) {
        if (!cruise.hasPosition) {
            status = "等待定位后再规划路线"
            return
        }
        recent = history.remember(place)
        selected = place
        status = "规划路线中…"
        NavTrace.log("MAP", "search preview open uid=${place.uid}")
        source.plan(
            NavPoint(cruise.latitude, cruise.longitude),
            NavPoint(place.lat, place.lng),
            place.uid,
        ) { ok ->
            if (!ok) status = "算路失败"
        }
    }

    // Baidu POI search requires a city; derive it from the current fix.
    LaunchedEffect(cruise.hasPosition, cruise.latitude, cruise.longitude) {
        if (!cruise.hasPosition) {
            status = "未定位：仍可搜索全国目的地，规划路线需等待定位"
            return@LaunchedEffect
        }
        geo.setOnGetGeoCodeResultListener(object : OnGetGeoCoderResultListener {
            override fun onGetGeoCodeResult(result: com.baidu.mapapi.search.geocode.GeoCodeResult?) = Unit

            override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
                city = result?.addressDetail?.city ?: ""
                if (city.isBlank()) status = "无法解析当前城市"
            }
        })
        val (bdLat, bdLng) = CoordUtil.wgs84ToBd09(cruise.latitude, cruise.longitude)
        runCatching { geo.reverseGeoCode(ReverseGeoCodeOption().location(LatLng(bdLat, bdLng))) }
    }

    LaunchedEffect(query, city, cruise.hasPosition) {
        if (query.isBlank()) {
            // Clearing the box is an async exit too: invalidate the previous query so
            // its in-flight callbacks cannot refill the list (VA-S01).
            searchSeq += 1
            searchHandler.removeCallbacksAndMessages(null)
            results = emptyList()
            status = ""
            return@LaunchedEffect
        }
        // Invalidate immediately, not after the 300 ms debounce, so typing a new query
        // cannot be overwritten by the previous query's results.
        val seq = searchSeq + 1
        searchSeq = seq
        searchHandler.removeCallbacksAndMessages(null)
        delay(300)
        val q = query
        val center = if (cruise.hasPosition) {
            val (bdLat, bdLng) = CoordUtil.wgs84ToBd09(cruise.latitude, cruise.longitude)
            LatLng(bdLat, bdLng)
        } else null
        status = "搜索中…"
        // Nearby first (complete + distance sorted); city-wide and keyword suggestions are
        // only fallbacks, in that order.
        runSearch(q, center, city, seq)
    }

    Column(Modifier.fillMaxSize().background(colors.bg).statusBarsPadding().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("返回", color = colors.muted, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onBack))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; selected = null },
                placeholder = { Text("搜索目的地（全国）", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (status.isNotBlank()) {
            Text(status, color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Text(navStatus, color = colors.dim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        if (selected == null) {
            if (query.isBlank() && recent.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("最近搜索", color = colors.muted, fontSize = 12.sp)
                            Text(
                                "清空",
                                color = colors.dim,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { recent = history.clear() },
                            )
                        }
                    }
                    items(recent) { place -> PlaceRow(place) { pick(place) } }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp)) {
                    items(results) { place -> PlaceRow(place) { pick(place) } }
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(18.dp))) {
                MapLayer(
                    ak = cruise.mapAk,
                    latitude = cruise.latitude,
                    longitude = cruise.longitude,
                    hasPosition = cruise.hasPosition,
                    showStatic = true,
                    loadStatic = true,
                    homeScale = 1f,
                    progress = progress,
                    route = navState.route,
                    fitRoute = navState.route.isNotEmpty(),
                    preview = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { index, tab ->
                    val selectedRoute = index == selectedTab
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(
                                1.dp,
                                if (selectedRoute) colors.mint else colors.text.copy(alpha = 0.16f),
                                RoundedCornerShape(14.dp),
                            )
                            .clickable { source.selectRoute(index) }
                            .padding(10.dp),
                    ) {
                        Text(
                            tab.label,
                            color = if (selectedRoute) colors.mint else colors.text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("${tab.lengthM / 1000}公里 · ${tab.timeSec / 60}分钟", color = colors.text, fontSize = 12.sp)
                        Text("${tab.lights} 个红绿灯", color = colors.muted, fontSize = 10.sp)
                    }
                }
            }
            Text(
                "开始导航",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.mint)
                    .clickable(enabled = navState.route.isNotEmpty()) {
                        onStartNav()
                    }
                    .padding(vertical = 13.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
/**
 * One search result: name on top, then where it is + how far, then the street address.
 * `PoiInfo` gives city/area/address for free, so same-named places (banks!) are finally
 * distinguishable instead of five identical rows.
 */
@Composable
private fun PlaceRow(place: SearchPlace, onClick: () -> Unit) {
    val colors = LocalSlmColors.current
    val meta = listOf(place.region, formatDistance(place.distanceM))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(place.name, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (meta.isNotBlank()) {
            Text(meta, color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (place.address.isNotBlank() && place.address != place.name) {
            Text(
                place.address,
                color = colors.dim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}
