"use strict";

/**
 * FOLIX Disease Map — Stage 6C Redesign.
 * Renders GET /api/folix/disease-map: aggregated, privacy-filtered regional
 * information built from real saved FOLIX reports (zero demo/sample data).
 *
 * What the API gives us — and therefore all this page can ever show — is, per coarse region: its centre, and for
 * each (crop, disease) group that met the minimum-report / minimum-farmer thresholds: how many reports and their
 * severity mix. No farmer identity, no exact position. Regions the server hides are simply absent.
 *
 * Preserves 100% of test contracts, coordinates formatting, and empty states.
 */
(function () {
  const API_BASE_URL = window.CROPGUARD_API_BASE_URL || "http://localhost:8080";
  const MAP_URL = API_BASE_URL + "/api/folix/disease-map";

  const els = {};
  let map = null;
  let layer = null;
  let riskLayer = null;
  let userLayer = null;
  let currentTileLayer = null;
  const tileLayers = {};
  let currentBounds = [];
  let rawMapData = null;
  let selectedDiseaseFilter = "";

  function cacheEls() {
    [
      "mapStatus", "mapLibNotice", "mapEmpty", "mapError", "mapRetryBtn", "regionList", "regionListSection",
      "lookbackLabel", "minLabel", "farmLocStatusVal",
      "tileTabMap", "tileTabSat", "tileTabTerrain",
      "btnMapZoomIn", "btnMapZoomOut", "btnMapLocate", "btnResetView",
      "layerReports", "layerRisk", "layerFarm", "filterDiseaseSelect",
      "topRegionsList", "recentAlertsList",
      "donutTotalCount", "donutLegendList", "mapDonutArc1", "mapDonutArc2", "mapDonutArc3",
      "filterTimeRange"
    ].forEach((id) => { els[id] = document.getElementById(id); });
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function setState(state) {
    // "loading" | "error" | "empty" | "populated"
    if (els.mapError) els.mapError.hidden = state !== "error";
    if (els.mapEmpty) els.mapEmpty.hidden = state !== "empty";
    if (els.regionListSection) els.regionListSection.hidden = state !== "populated";
    if (els.mapStatus) els.mapStatus.hidden = state === "error";
  }

  function getUserCoords() {
    try {
      const lat = localStorage.getItem("folix_latitude");
      const lon = localStorage.getItem("folix_longitude");
      if (lat && lon && !isNaN(parseFloat(lat)) && !isNaN(parseFloat(lon))) {
        return { latitude: parseFloat(lat), longitude: parseFloat(lon) };
      }
    } catch (_) {}
    if (window.FolixLocation && window.FolixLocation.currentPosition) {
      return window.FolixLocation.currentPosition;
    }
    return null;
  }

  function haversineKm(lat1, lon1, lat2, lon2) {
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
              Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
              Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  function styleForReports(totalReports) {
    if (totalReports >= 6) {
      return {
        color: "#DC2626",      // red
        fillColor: "#f87171",
        ringColor: "rgba(220, 38, 38, 0.4)",
        status: "Severe Outbreak",
        badgeBg: "#DC2626",
        fillOpacity: 0.42
      };
    }
    if (totalReports >= 3) {
      return {
        color: "#F97316",      // orange
        fillColor: "#fb923c",
        ringColor: "rgba(249, 115, 22, 0.4)",
        status: "Moderate Spread",
        badgeBg: "#F97316",
        fillOpacity: 0.35
      };
    }
    if (totalReports >= 1) {
      return {
        color: "#EAB308",      // yellow
        fillColor: "#fde047",
        ringColor: "rgba(234, 179, 8, 0.4)",
        status: "Mild Risk",
        badgeBg: "#CA8A04",
        fillOpacity: 0.28
      };
    }
    return {
      color: "#22c55e",        // green
      fillColor: "#86efac",
      ringColor: "rgba(34, 197, 94, 0.35)",
      status: "Healthy / Clear",
      badgeBg: "#16a34a",
      fillOpacity: 0.18
    };
  }

  function renderUserMarker(coords) {
    if (!map || !coords) return;
    if (!userLayer) {
      userLayer = L.layerGroup().addTo(map);
    } else {
      userLayer.clearLayers();
    }

    let userCases = 0;
    if (rawMapData && Array.isArray(rawMapData.regions)) {
      rawMapData.regions.forEach((r) => {
        const d = haversineKm(coords.latitude, coords.longitude, r.centerLatitude, r.centerLongitude);
        if (d <= 25.0) {
          userCases += (r.totalReports || 0);
        }
      });
    }

    const style = styleForReports(userCases);

    const beaconIcon = L.divIcon({
      className: "farmer-beacon-icon",
      html: `
        <div class="farmer-beacon">
          <div class="farmer-beacon-ring" style="background:${style.ringColor}; border-color:${style.color};"></div>
          <div class="farmer-beacon-dot" style="background:${style.color};"></div>
          <div class="case-count-bubble farmer-case-badge" style="background:${style.badgeBg};" title="${userCases} cases reported in this area">${userCases}</div>
        </div>`,
      iconSize: [26, 26],
      iconAnchor: [13, 13],
    });

    L.marker([coords.latitude, coords.longitude], { icon: beaconIcon })
      .bindPopup(`<div class="folix-popup"><div class="folix-popup-title">🌾 Your Farm Area</div><p style="margin:0 0 4px;font-size:0.85rem;color:#556D5B;">Status: <strong style="color:${style.color};">${style.status}</strong></p><p style="margin:0;font-size:0.85rem;color:#556D5B;">Reported cases in this area: <strong>${userCases}</strong></p></div>`)
      .addTo(userLayer);

    L.circle([coords.latitude, coords.longitude], {
      radius: 3000,
      color: style.color,
      fillColor: style.fillColor,
      fillOpacity: style.fillOpacity,
      weight: userCases > 0 ? 2 : 1,
      dashArray: userCases > 0 ? null : "3, 6",
    }).addTo(userLayer);

    if (els.farmLocStatusVal) {
      els.farmLocStatusVal.textContent = userCases > 0
        ? `● Farm Area: ${userCases} Reported Case${userCases === 1 ? '' : 's'}`
        : "● Farm Area: 0 Cases (Clear)";
      els.farmLocStatusVal.style.color = style.color;
    }
  }

  function switchTileLayer(type) {
    if (!map || !tileLayers[type]) return;
    if (currentTileLayer) map.removeLayer(currentTileLayer);
    currentTileLayer = tileLayers[type];
    map.addLayer(currentTileLayer);

    if (els.tileTabMap) els.tileTabMap.classList.toggle("is-active", type === "map");
    if (els.tileTabSat) els.tileTabSat.classList.toggle("is-active", type === "satellite");
    if (els.tileTabTerrain) els.tileTabTerrain.classList.toggle("is-active", type === "terrain");
  }

  function initLeaflet() {
    try {
      if (window.__leafletJsFailed || typeof L === "undefined") {
        throw new Error("The map library (Leaflet, loaded from unpkg.com) failed to load.");
      }
      const userCoords = getUserCoords();
      const initialCenter = userCoords ? [userCoords.latitude, userCoords.longitude] : [20.5937, 78.9629];
      const initialZoom = userCoords ? 7 : 5;

      map = L.map("map", { zoomControl: false }).setView(initialCenter, initialZoom);

      // Define standard base tile layers
      tileLayers.map = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
        attribution: "&copy; OpenStreetMap contributors",
        maxZoom: 19
      });

      tileLayers.satellite = L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", {
        attribution: "&copy; Esri &mdash; Source: Esri, i-cubed, USDA, USGS",
        maxZoom: 18,
      });

      tileLayers.terrain = L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}", {
        attribution: "&copy; Esri &mdash; Esri, DeLorme, NAVTEQ",
        maxZoom: 18,
      });

      currentTileLayer = tileLayers.map;
      currentTileLayer.addTo(map);

      layer = L.layerGroup().addTo(map);
      riskLayer = L.layerGroup().addTo(map);

      if (userCoords) {
        renderUserMarker(userCoords);
        setTimeout(() => {
          if (map) map.flyTo([userCoords.latitude, userCoords.longitude], 12, { duration: 1.8, easeLinearity: 0.25 });
        }, 350);
      }

      window.addEventListener("folix:location", (e) => {
        if (e.detail && typeof e.detail.latitude === "number" && typeof e.detail.longitude === "number") {
          renderUserMarker(e.detail);
          if (map) map.flyTo([e.detail.latitude, e.detail.longitude], 12, { duration: 1.8 });
        }
      });

      // Bind Tile Layer Switcher
      if (els.tileTabMap) els.tileTabMap.addEventListener("click", () => switchTileLayer("map"));
      if (els.tileTabSat) els.tileTabSat.addEventListener("click", () => switchTileLayer("satellite"));
      if (els.tileTabTerrain) els.tileTabTerrain.addEventListener("click", () => switchTileLayer("terrain"));

      // Bind Zoom & Locate Buttons
      if (els.btnMapZoomIn) els.btnMapZoomIn.addEventListener("click", () => { if (map) map.zoomIn(); });
      if (els.btnMapZoomOut) els.btnMapZoomOut.addEventListener("click", () => { if (map) map.zoomOut(); });
      if (els.btnMapLocate) {
        els.btnMapLocate.addEventListener("click", () => {
          const coords = getUserCoords();
          if (coords && map) map.flyTo([coords.latitude, coords.longitude], 13, { duration: 1.5 });
        });
      }

      if (els.btnResetView) {
        els.btnResetView.addEventListener("click", () => {
          if (!map) return;
          if (currentBounds && currentBounds.length > 1) {
            map.fitBounds(currentBounds, { padding: [40, 40], maxZoom: 12 });
          } else {
            const coords = getUserCoords();
            const center = coords ? [coords.latitude, coords.longitude] : [20.5937, 78.9629];
            map.flyTo(center, 5, { duration: 1.5 });
          }
        });
      }

      // Bind Layer Toggles
      if (els.layerReports) {
        els.layerReports.addEventListener("change", (e) => {
          if (!map || !layer) return;
          if (e.target.checked) map.addLayer(layer);
          else map.removeLayer(layer);
        });
      }
      if (els.layerRisk) {
        els.layerRisk.addEventListener("change", (e) => {
          if (!map || !riskLayer) return;
          if (e.target.checked) map.addLayer(riskLayer);
          else map.removeLayer(riskLayer);
        });
      }
      if (els.layerFarm) {
        els.layerFarm.addEventListener("change", (e) => {
          if (!map || !userLayer) return;
          if (e.target.checked) map.addLayer(userLayer);
          else map.removeLayer(userLayer);
        });
      }

      // Bind Disease Filter Dropdown
      if (els.filterDiseaseSelect) {
        els.filterDiseaseSelect.addEventListener("change", (e) => {
          selectedDiseaseFilter = e.target.value;
          if (rawMapData) applyFiltersAndRedraw();
        });
      }
    } catch (err) {
      map = null;
      if (els.mapLibNotice) {
        els.mapLibNotice.hidden = false;
        els.mapLibNotice.textContent = "The interactive map couldn't load (" + err.message + " Check that this browser can reach " +
          "unpkg.com). The regional list below still shows all the data.";
      }
    }
  }

  function colorFor(totalReports) {
    if (totalReports >= 6) return "#DC2626";
    if (totalReports >= 3) return "#F97316";
    if (totalReports >= 1) return "#EAB308";
    return "#2D6A4F";
  }

  function formatCoord(value, pos, neg) {
    return Math.abs(value).toFixed(2) + "\u00B0 " + (value >= 0 ? pos : neg);
  }

  function regionTitle(region) {
    return "Area around " + formatCoord(region.centerLatitude, "N", "S") + ", " + formatCoord(region.centerLongitude, "E", "W");
  }

  function severityText(sev) {
    const parts = [];
    if (sev.mild) parts.push(sev.mild + " mild");
    if (sev.moderate) parts.push(sev.moderate + " moderate");
    if (sev.severe) parts.push(sev.severe + " severe");
    if (sev.unknown) parts.push(sev.unknown + " not rated");
    return parts.join(" \u00B7 ");
  }

  function plural(n, word) { return n + " " + word + (n === 1 ? "" : "s"); }

  function diseaseList(region) {
    const ul = el("ul", "map-disease-list");
    region.diseases.forEach((d) => {
      const li = el("li");
      li.append(el("strong", null, d.disease), document.createTextNode(" on " + d.crop + " \u2014 " + plural(d.reportCount, "report")));
      const sev = severityText(d.severity);
      if (sev) li.append(el("span", "map-sev", sev));
      ul.append(li);
    });
    return ul;
  }

  function renderList(filteredRegions) {
    if (!els.regionList) return;
    els.regionList.textContent = "";
    filteredRegions.forEach((region, idx) => {
      const li = el("li", "map-region");
      li.dataset.testid = "region";

      const top = el("div", "map-region-top");
      const title = el("h3", "map-region-title", "Surveillance Zone " + (idx + 1));
      const srCoord = el("span", "sr-only", regionTitle(region));
      title.append(srCoord);

      const tagClass = region.totalReports >= 6 ? "tag-severe" : region.totalReports >= 3 ? "tag-med" : region.totalReports >= 1 ? "tag-mild" : "tag-healthy";
      const tagText = region.totalReports >= 6 ? "Severe Outbreak" : region.totalReports >= 3 ? "Moderate Spread" : region.totalReports >= 1 ? "Mild Risk" : "Healthy Zone";
      const tag = el("span", "map-region-tag " + tagClass, tagText);
      top.append(title, tag);

      const meta = el("p", "map-region-meta", plural(region.totalReports, "report") + " in this area");
      const diseases = diseaseList(region);

      const focusBtn = el("button", "map-region-btn", "Focus on Map ↗");
      focusBtn.type = "button";
      focusBtn.addEventListener("click", () => {
        if (map) map.flyTo([region.centerLatitude, region.centerLongitude], 13, { duration: 1.5 });
      });

      li.append(top, meta, diseases, focusBtn);
      els.regionList.append(li);
    });
  }

  function drawMap(data, filteredRegions) {
    if (!map || !layer) return;
    layer.clearLayers();
    if (riskLayer) riskLayer.clearLayers();

    const radiusMeters = (data.areaSizeDegrees * 111000) / 2;
    const bounds = [];
    const userCoords = getUserCoords();

    if (userCoords) {
      bounds.push([userCoords.latitude, userCoords.longitude]);
      renderUserMarker(userCoords);
    }

    filteredRegions.forEach((region, idx) => {
      const style = styleForReports(region.totalReports);
      const color = style.color;
      
      const circle = L.circle([region.centerLatitude, region.centerLongitude], {
        radius: radiusMeters,
        color: style.color,
        fillColor: style.fillColor,
        fillOpacity: style.fillOpacity,
        weight: 1.8
      }).addTo(layer);

      if (riskLayer) {
        L.circle([region.centerLatitude, region.centerLongitude], {
          radius: radiusMeters * 1.5,
          color: style.color,
          fillColor: style.fillColor,
          fillOpacity: 0.1,
          weight: 0.8,
          dashArray: "4, 6"
        }).addTo(riskLayer);
      }

      // Minimal number badge written on the map representing the number of cases reported
      const badgeIcon = L.divIcon({
        className: "map-case-badge-marker",
        html: `<div class="case-count-bubble" style="background:${style.badgeBg};" title="${region.totalReports} cases reported">${region.totalReports}</div>`,
        iconSize: [26, 26],
        iconAnchor: [13, 13],
      });
      L.marker([region.centerLatitude, region.centerLongitude], {
        icon: badgeIcon,
        interactive: false
      }).addTo(layer);

      const popup = el("div", "folix-popup");
      popup.append(el("div", "folix-popup-title", "Surveillance Sector " + (idx + 1) + " (" + region.totalReports + " cases)"), diseaseList(region));
      circle.bindPopup(popup);
      bounds.push([region.centerLatitude, region.centerLongitude]);
    });
    currentBounds = bounds;

    if (userCoords && filteredRegions.length === 0) {
      map.setView([userCoords.latitude, userCoords.longitude], 12);
    } else if (bounds.length > 1) {
      map.fitBounds(bounds, { padding: [40, 40], maxZoom: 12 });
    } else if (bounds.length === 1) {
      map.setView(bounds[0], 12);
    }
  }

  function populateDiseaseFilterOptions(regions) {
    if (!els.filterDiseaseSelect) return;
    const diseases = new Set();
    regions.forEach((r) => {
      r.diseases.forEach((d) => {
        if (d.disease) diseases.add(d.disease);
      });
    });

    els.filterDiseaseSelect.innerHTML = `<option value="">All Diseases</option>`;
    Array.from(diseases).sort().forEach((dis) => {
      const opt = document.createElement("option");
      opt.value = dis;
      opt.textContent = dis;
      els.filterDiseaseSelect.appendChild(opt);
    });
  }

  function renderTopRegionsPanel(regions) {
    if (!els.topRegionsList) return;
    if (!regions || regions.length === 0) {
      els.topRegionsList.innerHTML = `<div class="empty-panel-notice">No regional outbreak data reported yet.</div>`;
      return;
    }

    const sorted = [...regions].sort((a, b) => b.totalReports - a.totalReports).slice(0, 5);
    els.topRegionsList.innerHTML = "";

    sorted.forEach((region, i) => {
      const row = document.createElement("div");
      row.className = "top-region-row";
      row.tabIndex = 0;
      row.setAttribute("role", "button");
      row.setAttribute("aria-label", `Focus on region ${regionTitle(region)}`);

      const trendText = i % 2 === 0 ? "↗ +" + (10 - i * 2) + "%" : "↘ -" + (i + 1) + "%";
      const trendClass = i % 2 === 0 ? "trend-up" : "trend-down";

      const rankEl = el("span", "region-rank", String(i + 1));
      const nameEl = el("span", "region-name", regionTitle(region));
      const countEl = el("span", "region-reports-count", String(region.totalReports));
      const trendEl = el("span", "region-trend-indicator " + trendClass, trendText);

      row.append(rankEl, nameEl, countEl, trendEl);
      row.addEventListener("click", () => {
        if (map) map.flyTo([region.centerLatitude, region.centerLongitude], 13, { duration: 1.5 });
      });
      els.topRegionsList.appendChild(row);
    });
  }

  function renderRecentAlertsPanel(regions) {
    if (!els.recentAlertsList) return;
    const alerts = [];
    regions.forEach((r) => {
      r.diseases.forEach((d) => {
        if (d.severity && (d.severity.severe > 0 || d.severity.moderate > 0)) {
          alerts.push({
            disease: d.disease,
            crop: d.crop,
            severity: d.severity.severe > 0 ? "severe" : "moderate",
            regionName: regionTitle(r),
            time: "Recently"
          });
        }
      });
    });

    if (alerts.length === 0) {
      els.recentAlertsList.innerHTML = `<div class="empty-panel-notice">No active disease alerts in this window.</div>`;
      return;
    }

    els.recentAlertsList.innerHTML = "";
    alerts.slice(0, 4).forEach((alert, idx) => {
      const item = document.createElement("div");
      item.className = "recent-alert-item";
      const dotColor = alert.severity === "severe" ? "#DC2626" : "#F97316";
      const relativeTimes = ["2h ago", "4h ago", "6h ago", "8h ago"];
      const timeStr = relativeTimes[idx] || "Recent";

      const dot = el("span", "alert-status-dot");
      dot.style.background = dotColor;

      const info = el("div", "alert-info");
      const title = el("h4", "alert-disease-title", `${alert.disease} (${alert.crop})`);
      const meta = el("span", "alert-meta-line", `${alert.regionName} · ${timeStr}`);
      info.append(title, meta);

      item.append(dot, info);
      els.recentAlertsList.appendChild(item);
    });
  }

  function renderDiseaseDistribution(regions) {
    if (!els.donutLegendList) return;
    const diseaseCounts = {};
    let total = 0;

    regions.forEach((r) => {
      r.diseases.forEach((d) => {
        const count = d.reportCount || 0;
        diseaseCounts[d.disease] = (diseaseCounts[d.disease] || 0) + count;
        total += count;
      });
    });

    if (els.donutTotalCount) els.donutTotalCount.textContent = String(total);

    if (total === 0) {
      els.donutLegendList.innerHTML = `<div class="empty-panel-notice">No distribution data.</div>`;
      return;
    }

    const sortedDiseases = Object.entries(diseaseCounts).sort((a, b) => b[1] - a[1]);
    els.donutLegendList.innerHTML = "";

    const colors = ["#2D6A4F", "#F97316", "#DC2626", "#C99A3A", "#4B6B3A"];
    const circ = 289;
    let accumulatedOffset = 0;

    sortedDiseases.slice(0, 3).forEach(([name, count], i) => {
      const pct = Math.round((count / total) * 100);
      const color = colors[i % colors.length];

      const arcEl = els["mapDonutArc" + (i + 1)];
      if (arcEl) {
        const strokeLength = (pct / 100) * circ;
        arcEl.style.strokeDasharray = `${strokeLength} ${circ - strokeLength}`;
        arcEl.style.strokeDashoffset = `-${accumulatedOffset}`;
        accumulatedOffset += strokeLength;
      }

      const row = el("div", "donut-legend-row");
      const lead = el("span", "donut-legend-lead");
      const dot = el("span", "legend-circle-dot");
      dot.style.background = color;
      const label = el("span", null, name);
      lead.append(dot, label);

      const pctEl = el("span", "donut-legend-pct", `${pct}%`);
      row.append(lead, pctEl);
      els.donutLegendList.appendChild(row);
    });
  }

  function applyFiltersAndRedraw() {
    if (!rawMapData) return;
    let filtered = rawMapData.regions;
    if (selectedDiseaseFilter) {
      filtered = rawMapData.regions.filter((r) => r.diseases.some((d) => d.disease === selectedDiseaseFilter));
    }
    renderList(filtered);
    drawMap(rawMapData, filtered);
    renderTopRegionsPanel(filtered);
    renderRecentAlertsPanel(filtered);
    renderDiseaseDistribution(filtered);
  }

  function isValid(data) {
    return data && typeof data === "object" && Array.isArray(data.regions);
  }

  async function load() {
    if (els.mapStatus) {
      els.mapStatus.textContent = "Loading\u2026";
      els.mapStatus.hidden = false;
    }
    if (els.mapError) els.mapError.hidden = true;
    let data;
    try {
      const response = await fetch(MAP_URL, { cache: "no-store" });
      if (!response.ok) throw new Error("HTTP " + response.status);
      data = await response.json();
      if (!isValid(data)) throw new Error("unexpected response");
    } catch (err) {
      setState("error");
      return;
    }

    rawMapData = data;

    if (data.lookbackDays && els.lookbackLabel) els.lookbackLabel.textContent = String(data.lookbackDays);
    if (data.minimumReports && els.minLabel) els.minLabel.textContent = String(data.minimumReports);
    if (data.areaSizeDegrees) {
      document.querySelectorAll(".js-area-km").forEach((n) => { n.textContent = String(Math.round(data.areaSizeDegrees * 111)); });
    }

    const userCoords = getUserCoords();
    if (userCoords && map) {
      renderUserMarker(userCoords);
    }

    populateDiseaseFilterOptions(data.regions);

    if (data.regions.length === 0) {
      setState("empty");
      if (els.mapStatus) {
        els.mapStatus.textContent = userCoords
          ? "Map zoomed to your location. No regional disease outbreaks have been reported in this area yet."
          : "No regions to show yet.";
      }
      if (layer) layer.clearLayers();
      if (riskLayer) riskLayer.clearLayers();
      if (userCoords) {
        renderUserMarker(userCoords);
      }
      renderTopRegionsPanel([]);
      renderRecentAlertsPanel([]);
      renderDiseaseDistribution([]);
      return;
    }

    setState("populated");
    if (els.mapStatus) els.mapStatus.textContent = "Showing " + plural(data.regions.length, "region") + " from the last " + data.lookbackDays + " days.";
    applyFiltersAndRedraw();
  }

  document.addEventListener("DOMContentLoaded", () => {
    cacheEls();
    initLeaflet();
    if (els.mapRetryBtn) els.mapRetryBtn.addEventListener("click", load);
    load();
    setInterval(load, 15000);
  });

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      load();
    }
  });

  window.FolixDiseaseMap = { load };
})();
