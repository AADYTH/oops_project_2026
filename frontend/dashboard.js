"use strict";

/**
 * FOLIX farmer Dashboard — Stage 5C Redesign.
 *
 * Reads exclusively from the existing FOLIX report API:
 *   GET /api/scan/reports            (paginated scan history)
 *   GET /api/scan/reports/{id}       (one report's full detail)
 *   GET /api/scan/reports/summary    (overview counts)
 *
 * Stage 6A: every request is sent with the farmer's session cookie (credentials: "include").
 *
 * Preserves 100% of existing functionality, test selectors, API contracts,
 * empty states, loading states, error states, and modal detail views.
 */
(function () {
  const API_BASE_URL = window.CROPGUARD_API_BASE_URL || "http://localhost:8080";
  const REPORTS_URL = API_BASE_URL + "/api/scan/reports";
  const SUMMARY_URL = API_BASE_URL + "/api/scan/reports/summary";
  const DIRECTORY_URL = API_BASE_URL + "/api/farmer/agri-network/directory";
  const ALERTS_URL = API_BASE_URL + "/api/farmer/alerts";
  const PAGE_SIZE = 10;

  const CATEGORY_LABELS = {
    AGRICULTURAL_OFFICER: "Agricultural Officer",
    KRISHI_BHAVAN: "Krishi Bhavan",
    EXTENSION_SUPPORT: "Extension Support",
    CROP_DISEASE_ASSISTANCE: "Crop Disease Assistance",
    GENERAL_SUPPORT: "General Support",
  };

  let currentPage = 0;
  let currentDetailReportId = null;
  let cachedReports = [];

  const els = {};

  function cacheEls() {
    [
      "dashUserGreeting",
      "dashOverview", "dashStatTotal", "dashStatHealthy", "dashStatDiseased", "dashStatReview",
      "dashTrendTotal", "dashTrendHealthy", "dashTrendDiseased", "dashTrendReview",
      "dashLoading", "dashError", "dashAuthRequired", "dashErrorMessage", "dashRetryBtn",
      "dashEmpty", "dashAnalyticsSection", "dashActivityList", "dashActivityViewAll",
      "dashLineChartSvg", "dashChartBody", "dashChartTooltip", "dashTooltipDate", "dashTooltipVal",
      "dashChartXAxis", "chartAreaHealthy", "chartLineHealthy", "chartAreaRisk", "chartLineRisk",
      "chartAreaDiseased", "chartLineDiseased", "chartPointActive",
      "donutHealthyCircle", "donutRiskCircle", "donutDiseasedCircle",
      "dashDonutPercent", "dashDonutLabel",
      "dashLegendHealthyPct", "dashLegendHealthyCount",
      "dashLegendRiskPct", "dashLegendRiskCount",
      "dashLegendDiseasedPct", "dashLegendDiseasedCount",
      "dashHistorySection", "dashHistoryCount", "dashHistoryList",
      "dashPagination", "dashPrevBtn", "dashNextBtn", "dashPageLabel",
      "dashOverlay", "dashPanelCrop", "dashPanelDisease", "dashPanelDate",
      "dashPanelBody", "dashPanelCloseBtn",
      "dashForwardSection", "dashForwardStatus", "dashForwardBtn",
      "dashForwardPicker", "dashForwardContactSelect",
      "dashForwardConfirmBtn", "dashForwardCancelBtn", "dashForwardMessage",
      "dashAlertTriggerSection", "dashAlertTriggerBtn", "dashAlertTriggerSub",
      "dashAlertTriggerMessage", "dashAlertViewLink",
      "dashEscalateSection", "dashEscalateBtn", "dashEscalateSub", "dashEscalateMessage",
    ].forEach((id) => { els[id] = document.getElementById(id); });
  }

  function escapeHtml(str) {
    const div = document.createElement("div");
    div.textContent = str == null ? "" : String(str);
    return div.innerHTML;
  }

  function setState(state) {
    // state: "loading" | "error" | "auth" | "empty" | "populated"
    if (els.dashAuthRequired) els.dashAuthRequired.hidden = state !== "auth";
    if (els.dashLoading) els.dashLoading.hidden = state !== "loading";
    if (els.dashError) els.dashError.hidden = state !== "error";
    if (els.dashEmpty) els.dashEmpty.hidden = state !== "empty";
    if (els.dashHistorySection) els.dashHistorySection.hidden = state !== "populated";
    if (els.dashAnalyticsSection) els.dashAnalyticsSection.hidden = state !== "populated";
    if (els.dashOverview) els.dashOverview.hidden = state === "loading" || state === "error" || state === "auth";
  }

  function reportErrorMessage(payload, fallback) {
    const err = payload && typeof payload === "object" && payload.error && typeof payload.error === "object" ? payload.error : null;
    return err && typeof err.message === "string" && err.message.length > 0 && err.message.length <= 300 ? err.message : fallback;
  }

  async function fetchJson(url) {
    let response;
    try {
      response = await fetch(url, { credentials: "include", cache: "no-store" });
    } catch (networkErr) {
      throw new Error("Could not reach the FOLIX server. Check your connection and try again.");
    }
    let payload = null;
    try {
      payload = await response.json();
    } catch (parseErr) {
      // empty response
    }
    if (response.status === 401) {
      const authErr = new Error("Please log in to view your dashboard.");
      authErr.authRequired = true;
      throw authErr;
    }
    if (!response.ok) {
      throw new Error(reportErrorMessage(payload, `The server returned an unexpected error (HTTP ${response.status}).`));
    }
    return payload;
  }

  async function postJson(url, body) {
    let response;
    try {
      response = await fetch(url, {
        method: "POST",
        credentials: "include",
        cache: "no-store",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
    } catch (networkErr) {
      throw new Error("Could not reach the FOLIX server. Check your connection and try again.");
    }
    let payload = null;
    try {
      payload = await response.json();
    } catch (parseErr) {
      // empty body
    }
    if (response.status === 401) {
      const authErr = new Error("Please log in to view your dashboard.");
      authErr.authRequired = true;
      throw authErr;
    }
    if (!response.ok) {
      const err = payload && typeof payload === "object" && payload.error && typeof payload.error === "object" ? payload.error : null;
      const forwardErr = new Error(reportErrorMessage(payload, `The server returned an unexpected error (HTTP ${response.status}).`));
      forwardErr.code = err && typeof err.code === "string" ? err.code : "";
      throw forwardErr;
    }
    return payload;
  }

  function titleCase(str) {
    const s = String(str || "");
    return s.length ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  }

  function percentSuffix(v) {
    return typeof v === "number" && Number.isFinite(v) ? ` (${Math.round(v)}%)` : "";
  }

  function severityPillClass(sev) {
    return "sev-" + (["mild", "moderate", "severe"].includes(sev) ? sev : "unknown");
  }

  function confidencePillClass(conf) {
    return "conf-" + (["high", "moderate", "low"].includes(conf) ? conf : "unknown");
  }

  function formatDate(iso) {
    if (!iso) return "Unknown date";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "Unknown date";
    return d.toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
  }

  function formatRelativeTime(iso) {
    if (!iso) return "Recently";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "Recently";
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffSec = Math.floor(diffMs / 1000);
    const diffMin = Math.floor(diffSec / 60);
    const diffHour = Math.floor(diffMin / 60);
    const diffDays = Math.floor(diffHour / 24);

    if (diffMin < 2) return "Just now";
    if (diffMin < 60) return `${diffMin} mins ago`;
    if (diffHour < 24) return `${diffHour} hour${diffHour === 1 ? "" : "s"} ago`;
    if (diffDays === 1) return "Yesterday";
    if (diffDays < 7) return `${diffDays} days ago`;
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }

  // ---------------------------------------------------------------
  // Overview & Analytics Data
  // ---------------------------------------------------------------

  async function loadOverview() {
    const stats = await fetchJson(SUMMARY_URL);
    const total = stats && typeof stats.totalReports === "number" ? stats.totalReports : 0;
    const needsReview = stats && typeof stats.reportsNeedingHumanReview === "number" ? stats.reportsNeedingHumanReview : 0;
    
    if (els.dashStatTotal) els.dashStatTotal.textContent = String(total);
    if (els.dashStatReview) {
      els.dashStatReview.textContent = String(needsReview);
      els.dashStatReview.classList.toggle("is-attention", needsReview > 0);
    }
    return { total, needsReview };
  }

  function updateAnalyticsFromReports(reports, totalReports) {
    cachedReports = reports || [];

    let healthyCount = 0;
    let riskCount = 0;
    let diseasedCount = 0;

    reports.forEach((r) => {
      const isHealthy = (r.disease && r.disease.trim().toLowerCase() === "healthy") || r.severity === "mild";
      const isEpidemicOrSevere = r.epidemicRisk === true || r.severity === "severe";
      
      if (isHealthy) {
        healthyCount++;
      } else if (isEpidemicOrSevere) {
        diseasedCount++;
      } else {
        riskCount++;
      }
    });

    if (els.dashStatHealthy) els.dashStatHealthy.textContent = String(healthyCount);
    if (els.dashStatDiseased) els.dashStatDiseased.textContent = String(diseasedCount);

    // Distribution Donut Math
    const totalCount = reports.length || totalReports || 0;
    if (totalCount > 0) {
      const healthyPct = Math.round((healthyCount / totalCount) * 100);
      const riskPct = Math.round((riskCount / totalCount) * 100);
      const diseasedPct = Math.max(0, 100 - healthyPct - riskPct);

      if (els.dashDonutPercent) els.dashDonutPercent.textContent = `${healthyPct}%`;
      if (els.dashDonutLabel) els.dashDonutLabel.textContent = "Healthy";

      if (els.dashLegendHealthyPct) els.dashLegendHealthyPct.textContent = `${healthyPct}%`;
      if (els.dashLegendHealthyCount) els.dashLegendHealthyCount.textContent = String(healthyCount);

      if (els.dashLegendRiskPct) els.dashLegendRiskPct.textContent = `${riskPct}%`;
      if (els.dashLegendRiskCount) els.dashLegendRiskCount.textContent = String(riskCount);

      if (els.dashLegendDiseasedPct) els.dashLegendDiseasedPct.textContent = `${diseasedPct}%`;
      if (els.dashLegendDiseasedCount) els.dashLegendDiseasedCount.textContent = String(diseasedCount);

      // SVG Donut circumference: C = 2 * PI * 62 = ~389.55
      const circ = 389.55;
      const healthyArc = (healthyPct / 100) * circ;
      const riskArc = (riskPct / 100) * circ;
      const diseasedArc = (diseasedPct / 100) * circ;

      if (els.donutHealthyCircle) {
        els.donutHealthyCircle.style.strokeDasharray = `${healthyArc} ${circ - healthyArc}`;
        els.donutHealthyCircle.style.strokeDashoffset = "0";
      }
      if (els.donutRiskCircle) {
        els.donutRiskCircle.style.strokeDasharray = `${riskArc} ${circ - riskArc}`;
        els.donutRiskCircle.style.strokeDashoffset = `-${healthyArc}`;
      }
      if (els.donutDiseasedCircle) {
        els.donutDiseasedCircle.style.strokeDasharray = `${diseasedArc} ${circ - diseasedArc}`;
        els.donutDiseasedCircle.style.strokeDashoffset = `-${healthyArc + riskArc}`;
      }
    } else {
      if (els.dashDonutPercent) els.dashDonutPercent.textContent = "0%";
      if (els.dashDonutLabel) els.dashDonutLabel.textContent = "No data";
      if (els.donutHealthyCircle) els.donutHealthyCircle.style.strokeDasharray = `0 390`;
      if (els.donutRiskCircle) els.donutRiskCircle.style.strokeDasharray = `0 390`;
      if (els.donutDiseasedCircle) els.donutDiseasedCircle.style.strokeDasharray = `0 390`;
    }

    renderRecentActivity(reports);
    updateOverviewChart(reports);
  }

  function updateOverviewChart(reports) {
    if (!els.dashChartXAxis || !els.dashLineChartSvg) return;

    // 1. Build the 7-day chronological buckets (from 6 days ago up to today)
    const now = new Date();
    const days = [];
    for (let i = 6; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
      const dayStart = d.getTime();
      const nextDayStart = dayStart + 24 * 60 * 60 * 1000;
      days.push({
        date: d,
        dayStart,
        nextDayStart,
        label: d.toLocaleDateString(undefined, { month: "short", day: "numeric" }),
        healthy: 0,
        risk: 0,
        diseased: 0,
        total: 0
      });
    }

    // 2. Aggregate counts from actual farmer scan reports
    (reports || []).forEach((r) => {
      if (!r.createdAt) return;
      const t = new Date(r.createdAt).getTime();
      const bucket = days.find((b) => t >= b.dayStart && t < b.nextDayStart);
      if (!bucket) return;

      const isHealthy = (r.disease && r.disease.trim().toLowerCase() === "healthy") || r.severity === "mild";
      const isSevere = r.severity === "severe" || r.epidemicRisk === true;
      if (isHealthy) bucket.healthy++;
      else if (isSevere) bucket.diseased++;
      else bucket.risk++;
      bucket.total++;
    });

    // 3. Render X-Axis labels
    els.dashChartXAxis.innerHTML = days.map((b) => `<span>${escapeHtml(b.label)}</span>`).join("");

    // 4. Calculate SVG coordinates: viewBox="0 0 680 220"
    // Margins: left=30, right=650, span=620; baseline y=200, top y=35
    const xCoords = days.map((_, i) => Math.round(30 + (i / 6) * 620));
    const maxVal = Math.max(1, ...days.map((b) => Math.max(b.healthy, b.risk, b.diseased)));

    const getY = (val) => {
      if (val <= 0) return 200;
      return Math.round(200 - (val / maxVal) * 165);
    };

    // Helper: Build SVG smooth spline path through (x, y) points
    const buildPath = (points) => {
      if (!points.length) return "";
      let d = `M ${points[0].x} ${points[0].y}`;
      for (let i = 0; i < points.length - 1; i++) {
        const p0 = points[i];
        const p1 = points[i + 1];
        const mx = (p0.x + p1.x) / 2;
        d += ` C ${mx} ${p0.y}, ${mx} ${p1.y}, ${p1.x} ${p1.y}`;
      }
      return d;
    };

    const healthyPts = days.map((b, i) => ({ x: xCoords[i], y: getY(b.healthy) }));
    const riskPts = days.map((b, i) => ({ x: xCoords[i], y: getY(b.risk) }));
    const diseasedPts = days.map((b, i) => ({ x: xCoords[i], y: getY(b.diseased) }));

    const healthyLineD = buildPath(healthyPts);
    const riskLineD = buildPath(riskPts);
    const diseasedLineD = buildPath(diseasedPts);

    const healthyAreaD = `${healthyLineD} L ${xCoords[6]} 200 L ${xCoords[0]} 200 Z`;
    const riskAreaD = `${riskLineD} L ${xCoords[6]} 200 L ${xCoords[0]} 200 Z`;
    const diseasedAreaD = `${diseasedLineD} L ${xCoords[6]} 200 L ${xCoords[0]} 200 Z`;

    if (els.chartLineHealthy) els.chartLineHealthy.setAttribute("d", healthyLineD);
    if (els.chartAreaHealthy) els.chartAreaHealthy.setAttribute("d", healthyAreaD);

    if (els.chartLineRisk) els.chartLineRisk.setAttribute("d", riskLineD);
    if (els.chartAreaRisk) els.chartAreaRisk.setAttribute("d", riskAreaD);

    if (els.chartLineDiseased) els.chartLineDiseased.setAttribute("d", diseasedLineD);
    if (els.chartAreaDiseased) els.chartAreaDiseased.setAttribute("d", diseasedAreaD);

    // 5. Add interactive hover dots for real scan metrics
    let dotsGroup = els.dashLineChartSvg.querySelector("#dashChartDotsGroup");
    if (!dotsGroup) {
      dotsGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      dotsGroup.setAttribute("id", "dashChartDotsGroup");
      els.dashLineChartSvg.appendChild(dotsGroup);
    }
    dotsGroup.innerHTML = "";

    days.forEach((b, i) => {
      const highestY = Math.min(healthyPts[i].y, riskPts[i].y, diseasedPts[i].y);
      const dotX = xCoords[i];

      const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      circle.setAttribute("cx", dotX);
      circle.setAttribute("cy", highestY);
      circle.setAttribute("r", b.total > 0 ? "5.5" : "3.5");
      circle.setAttribute("fill", b.diseased > 0 ? "#DC2626" : b.risk > 0 ? "#D97706" : b.healthy > 0 ? "#2D6A4F" : "#94A3B8");
      circle.setAttribute("stroke", "#ffffff");
      circle.setAttribute("stroke-width", "2");
      circle.style.cursor = "pointer";
      circle.style.transition = "transform 0.15s ease";

      const showTooltip = () => {
        if (!els.dashChartTooltip) return;
        if (els.dashTooltipDate) els.dashTooltipDate.textContent = b.label;
        if (els.dashTooltipVal) {
          if (b.total === 0) {
            els.dashTooltipVal.textContent = "No scans on this day";
          } else {
            els.dashTooltipVal.innerHTML = `<b>${b.total} scan${b.total === 1 ? '' : 's'}:</b> ${b.healthy} healthy &bull; ${b.risk} at risk &bull; ${b.diseased} severe`;
          }
        }
        const pctX = (dotX / 680) * 100;
        const pctY = (highestY / 220) * 100;
        els.dashChartTooltip.style.left = `${Math.min(85, Math.max(15, pctX))}%`;
        els.dashChartTooltip.style.top = `${Math.max(12, pctY - 18)}%`;
        els.dashChartTooltip.style.display = "block";
      };

      circle.addEventListener("mouseenter", showTooltip);
      circle.addEventListener("click", showTooltip);
      dotsGroup.appendChild(circle);
    });

    // Default active dot at latest day (index 6)
    if (els.chartPointActive) {
      els.chartPointActive.setAttribute("cx", xCoords[6]);
      els.chartPointActive.setAttribute("cy", Math.min(healthyPts[6].y, riskPts[6].y, diseasedPts[6].y));
    }
  }

  function renderRecentActivity(reports) {
    if (!els.dashActivityList) return;
    if (!reports || reports.length === 0) {
      els.dashActivityList.innerHTML = `<div class="dash-activity-empty"><p>No recent activity yet.</p></div>`;
      return;
    }

    const recent = reports.slice(0, 4);
    els.dashActivityList.innerHTML = "";

    recent.forEach((r) => {
      const item = document.createElement("div");
      item.className = "dash-activity-item";
      item.tabIndex = 0;
      item.setAttribute("role", "button");
      item.setAttribute("aria-label", `View scan for ${r.crop} - ${r.disease}`);

      const isHealthy = (r.disease && r.disease.trim().toLowerCase() === "healthy") || r.severity === "mild";
      const isSevere = r.severity === "severe" || r.epidemicRisk === true;
      
      let badgeClass = "badge-amber";
      let badgeLabel = "At Risk";
      if (isHealthy) {
        badgeClass = "badge-green";
        badgeLabel = "Healthy";
      } else if (isSevere) {
        badgeClass = "badge-coral";
        badgeLabel = "Severe";
      }

      const conditionText = isHealthy ? "Healthy foliage" : `${titleCase(r.disease)} detected`;

      item.innerHTML = `
        <div class="activity-thumb-box" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 2C6.5 6 4 12 4 18a8 8 0 0 0 16 0c0-6-2.5-12-8-16z"/>
            <path d="M12 6v12"/>
          </svg>
        </div>
        <div class="activity-info">
          <div class="activity-crop-row">
            <h4 class="activity-crop-name">${escapeHtml(r.crop)}</h4>
          </div>
          <p class="activity-condition">${escapeHtml(conditionText)}</p>
          <span class="activity-time">${escapeHtml(formatRelativeTime(r.createdAt))}</span>
        </div>
        <div class="activity-meta">
          <span class="activity-badge ${badgeClass}">${escapeHtml(badgeLabel)}</span>
          <span class="activity-arrow" aria-hidden="true">›</span>
        </div>
      `;

      item.addEventListener("click", () => openReportDetail(r.id));
      item.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          openReportDetail(r.id);
        }
      });

      els.dashActivityList.appendChild(item);
    });
  }

  // ---------------------------------------------------------------
  // Scan History List & Recent Scans Table
  // ---------------------------------------------------------------

  function getConfidenceNumeric(conf) {
    if (typeof conf === "number" && Number.isFinite(conf)) return Math.round(conf);
    if (conf === "high") return 94;
    if (conf === "moderate") return 76;
    if (conf === "low") return 48;
    return 80;
  }

  function renderHistoryRow(report) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "dash-row";
    row.setAttribute("role", "listitem");
    row.dataset.reportId = report.id;

    const needsReview = !!report.needsHumanReview;
    const epidemicRisk = report.epidemicRisk === true;
    const epidemicRiskBadgeHtml = epidemicRisk
      ? `<span class="dash-epidemic-badge dash-row-epidemic-risk">⚠️ Epidemic risk</span>`
      : "";

    const confPct = getConfidenceNumeric(report.confidence);

    row.innerHTML = `
      <div class="dash-row-cell cell-crop">
        <span class="dash-row-crop">${escapeHtml(report.crop)}</span>
      </div>

      <div class="dash-row-cell cell-image" aria-hidden="true">
        <div class="dash-row-thumb">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 2C6.5 6 4 12 4 18a8 8 0 0 0 16 0c0-6-2.5-12-8-16z"/>
            <path d="M12 6v12"/>
          </svg>
        </div>
      </div>

      <div class="dash-row-cell cell-disease">
        <div class="dash-row-primary">
          <span class="dash-row-disease">${escapeHtml(report.disease)}</span>
          <div class="dash-pills-wrap">
            <span class="dash-pill ${severityPillClass(report.severity)} dash-row-severity">${escapeHtml(titleCase(report.severity))}</span>
            <span class="dash-pill ${needsReview ? "review-flagged" : "review-clear"} dash-row-review">${needsReview ? "Needs review" : "Reviewed"}</span>
            ${epidemicRiskBadgeHtml}
          </div>
        </div>
      </div>

      <div class="dash-row-cell cell-conf">
        <div class="dash-conf-box">
          <span class="dash-pill ${confidencePillClass(report.confidence)} dash-row-confidence">${escapeHtml(titleCase(report.confidence))}</span>
          <div class="dash-conf-progress" aria-hidden="true">
            <span class="dash-conf-num">${confPct}%</span>
            <div class="dash-conf-bar"><div class="dash-conf-fill" style="width: ${confPct}%;"></div></div>
          </div>
        </div>
      </div>

      <div class="dash-row-cell cell-date">
        <span class="dash-row-date">${escapeHtml(formatDate(report.createdAt))}</span>
      </div>

      <div class="dash-row-cell cell-action">
        <span class="dash-row-action-btn">
          <span>View Details</span>
          <span class="dash-row-chevron" aria-hidden="true">→</span>
        </span>
      </div>
    `;

    row.addEventListener("click", () => openReportDetail(report.id));
    return row;
  }

  async function loadHistory(page) {
    const url = `${REPORTS_URL}?page=${encodeURIComponent(page)}&size=${PAGE_SIZE}`;
    const data = await fetchJson(url);
    const reports = Array.isArray(data && data.reports) ? data.reports : [];
    const totalElements = data && typeof data.totalElements === "number" ? data.totalElements : reports.length;
    const totalPages = data && typeof data.totalPages === "number" ? data.totalPages : 1;
    const resolvedPage = data && typeof data.page === "number" ? data.page : page;

    if (els.dashHistoryList) {
      els.dashHistoryList.innerHTML = "";
      reports.forEach((report) => els.dashHistoryList.appendChild(renderHistoryRow(report)));
    }

    if (els.dashHistoryCount) {
      els.dashHistoryCount.textContent = totalElements === 1 ? "1 scan" : `${totalElements} scans`;
    }

    if (els.dashPagination) els.dashPagination.hidden = totalPages <= 1;
    if (els.dashPrevBtn) els.dashPrevBtn.disabled = resolvedPage <= 0;
    if (els.dashNextBtn) els.dashNextBtn.disabled = resolvedPage >= totalPages - 1;
    if (els.dashPageLabel) els.dashPageLabel.textContent = `Page ${resolvedPage + 1} of ${totalPages}`;

    currentPage = resolvedPage;
    updateAnalyticsFromReports(reports, totalElements);
    return totalElements;
  }

  // ---------------------------------------------------------------
  // Report Detail Overlay
  // ---------------------------------------------------------------

  function renderPanelList(items) {
    if (!Array.isArray(items) || items.length === 0) return "<p>None recorded.</p>";
    return `<ul>${items.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul>`;
  }

  function renderRemedyPlanBlock(remedyPlan) {
    const noRemedyHtml = `
      <div class="dash-panel-block">
        <h4>Remedies</h4>
        <p>No specific remedy guidance available for this diagnosis yet — consult a local agronomist.</p>
      </div>
    `;
    if (!remedyPlan || typeof remedyPlan !== "object") return noRemedyHtml;

    const organic = Array.isArray(remedyPlan.organicRemedies) ? remedyPlan.organicRemedies : [];
    const chemical = Array.isArray(remedyPlan.chemicalRemedies) ? remedyPlan.chemicalRemedies : [];
    if (organic.length === 0 && chemical.length === 0) return noRemedyHtml;

    const organicHtml = organic.length
      ? `<ul>${organic.map((r) => `
          <li>
            <strong>${escapeHtml(r.name || "")}</strong> — ${escapeHtml(r.instructions || "")}
            ${r.applicationFrequency ? ` (Frequency: ${escapeHtml(r.applicationFrequency)})` : ""}
          </li>`).join("")}</ul>`
      : "<p>None recorded.</p>";

    const chemicalHtml = chemical.length
      ? `<ul>${chemical.map((r) => `
          <li>
            <strong>${escapeHtml(r.name || "")}</strong> — ${escapeHtml(r.instructions || "")}
            ${r.activeIngredient ? ` (Active ingredient: ${escapeHtml(r.activeIngredient)})` : ""}
            ${r.dosage ? ` (Dosage: ${escapeHtml(r.dosage)})` : ""}
            ${r.preHarvestIntervalDays ? ` (Pre-harvest interval: ${escapeHtml(r.preHarvestIntervalDays)} days)` : ""}
            ${r.safetyPrecautions ? ` (Safety: ${escapeHtml(r.safetyPrecautions)})` : ""}
          </li>`).join("")}</ul>`
      : "<p>None recorded.</p>";

    return `
      <div class="dash-panel-block">
        <h4>Organic remedies</h4>
        ${organicHtml}
      </div>
      <div class="dash-panel-block">
        <h4>Chemical remedies</h4>
        ${chemicalHtml}
      </div>
    `;
  }

  async function openReportDetail(id) {
    els.dashOverlay.hidden = false;
    els.dashPanelCrop.textContent = "";
    els.dashPanelDisease.textContent = "Loading…";
    els.dashPanelDate.textContent = "";
    els.dashPanelBody.innerHTML = `<div class="dash-panel-body"><div class="dash-spinner" aria-hidden="true"></div>Loading report…</div>`;
    currentDetailReportId = id;
    resetForwardUi();
    resetAlertTriggerUi();
    loadForwardStatus(id);

    try {
      const report = await fetchJson(`${REPORTS_URL}/${encodeURIComponent(id)}`);
      const needsReview = !!report.needsHumanReview;
      const epidemicRiskBannerHtml = report.epidemicRisk === true
        ? `<div class="dash-danger-banner">
             <p><strong>⚠️ Epidemic-risk disease.</strong> This disease spreads fast — consider warning nearby farmers.</p>
           </div>`
        : "";

      els.dashPanelCrop.textContent = report.crop;
      els.dashPanelDisease.textContent = report.disease;
      els.dashPanelDate.textContent = formatDate(report.createdAt);

      els.dashPanelBody.innerHTML = `
        ${epidemicRiskBannerHtml}
        <div class="dash-panel-pills">
          <span class="dash-pill ${severityPillClass(report.severity)}">Severity: ${escapeHtml(titleCase(report.severity))}${percentSuffix(report.severityPercent)}</span>
          ${Number.isFinite(report.spreadChancePercent) ? `<span class="dash-pill ${severityPillClass(report.spreadChancePercent >= 60 ? "severe" : report.spreadChancePercent >= 30 ? "moderate" : "mild")}" title="Estimate from the disease type and visible severity — not a forecast">Spread chance: ${Math.round(report.spreadChancePercent)}%</span>` : ""}
          <span class="dash-pill ${confidencePillClass(report.confidence)}">Confidence: ${escapeHtml(titleCase(report.confidence))}</span>
          <span class="dash-pill ${needsReview ? "review-flagged" : "review-clear"}">${needsReview ? "Needs human review" : "Reviewed"}</span>
        </div>
        <div class="dash-panel-block">
          <h4>Plant part</h4>
          <p>${escapeHtml(titleCase(String(report.plantPart || "").replace("_", " ")))}</p>
        </div>
        <div class="dash-panel-block">
          <h4>Symptoms observed</h4>
          ${renderPanelList(report.symptoms)}
        </div>
        <div class="dash-panel-block">
          <h4>Reasoning</h4>
          <p>${escapeHtml(report.reasoning)}</p>
        </div>
        <div class="dash-panel-block">
          <h4>Recommended actions</h4>
          ${renderPanelList(report.recommendedActions)}
        </div>
        ${renderRemedyPlanBlock(report.remedyPlan)}
      `;

      if (report.epidemicRisk === true) {
        showAlertTriggerSection();
      } else if (report.disease && !/^healthy$/i.test(report.disease.trim()) && report.disease.trim().toLowerCase() !== "unknown") {
        showEscalateSection();
      }
    } catch (err) {
      if (err.authRequired) {
        closeReportDetail();
        setState("auth");
        return;
      }
      els.dashPanelDisease.textContent = "Couldn't load this report";
      els.dashPanelBody.innerHTML = `<div class="dash-panel-body"><p>${escapeHtml(err.message)}</p></div>`;
    }
  }

  function closeReportDetail() {
    els.dashOverlay.hidden = true;
    resetForwardUi();
    resetAlertTriggerUi();
    currentDetailReportId = null;
  }

  // ---------------------------------------------------------------
  // Alert Trigger & Authority Escalation
  // ---------------------------------------------------------------

  function resetAlertTriggerUi() {
    if (els.dashAlertTriggerSection) {
      els.dashAlertTriggerSection.hidden = true;
      els.dashAlertTriggerBtn.disabled = false;
      els.dashAlertTriggerBtn.textContent = "Send alert";
      els.dashAlertTriggerMessage.hidden = true;
      els.dashAlertTriggerMessage.textContent = "";
      els.dashAlertTriggerMessage.className = "dash-alert-trigger-message";
      els.dashAlertViewLink.hidden = true;
    }

    if (els.dashEscalateSection) {
      els.dashEscalateSection.hidden = true;
      els.dashEscalateBtn.disabled = false;
      els.dashEscalateBtn.textContent = "Alert Authority";
      els.dashEscalateMessage.hidden = true;
      els.dashEscalateMessage.textContent = "";
      els.dashEscalateMessage.className = "dash-alert-trigger-message";
    }
  }

  function showAlertTriggerSection() {
    if (!els.dashAlertTriggerSection) return;
    els.dashAlertTriggerSection.hidden = false;
    els.dashAlertTriggerMessage.hidden = true;
    els.dashAlertTriggerMessage.textContent = "";
    els.dashAlertTriggerMessage.className = "dash-alert-trigger-message";
    els.dashAlertViewLink.hidden = true;
    els.dashAlertTriggerBtn.disabled = false;
    els.dashAlertTriggerBtn.textContent = "Send alert";
    if (els.dashEscalateSection) els.dashEscalateSection.hidden = true;
  }

  function showEscalateSection() {
    if (!els.dashEscalateSection) return;
    els.dashEscalateSection.hidden = false;
    els.dashEscalateMessage.hidden = true;
    els.dashEscalateMessage.textContent = "";
    els.dashEscalateMessage.className = "dash-alert-trigger-message";
    els.dashEscalateBtn.disabled = false;
    els.dashEscalateBtn.textContent = "Alert Authority";
    if (els.dashAlertTriggerSection) els.dashAlertTriggerSection.hidden = true;
  }

  async function escalateToAuthority() {
    if (!currentDetailReportId || !els.dashEscalateBtn) return;
    const farmerNote = window.prompt(
      "Tell the authority why you think the spread risk is higher than the AI estimates:\n\n(Optional — click OK to proceed)"
    );
    if (farmerNote === null) return;

    els.dashEscalateBtn.disabled = true;
    els.dashEscalateBtn.textContent = "Alerting authority…";
    els.dashEscalateMessage.hidden = true;

    let escLat = null;
    let escLon = null;
    try {
      const storedLat = localStorage.getItem("folix_latitude");
      const storedLon = localStorage.getItem("folix_longitude");
      if (storedLat && storedLon && !isNaN(parseFloat(storedLat)) && !isNaN(parseFloat(storedLon))) {
        escLat = parseFloat(storedLat);
        escLon = parseFloat(storedLon);
      }
    } catch (_) {}
    if ((escLat == null || escLon == null) && window.FolixLocation && window.FolixLocation.currentPosition) {
      escLat = window.FolixLocation.currentPosition.latitude;
      escLon = window.FolixLocation.currentPosition.longitude;
    }

    try {
      const result = await postJson(
        `${ALERTS_URL}/escalate-to-authority/${encodeURIComponent(currentDetailReportId)}`,
        { note: farmerNote ? farmerNote.trim() : null, latitude: escLat, longitude: escLon }
      );
      els.dashEscalateBtn.textContent = "Authority Alerted ✓";
      els.dashEscalateMessage.textContent = result && result.message
        ? result.message
        : "Alert sent to the local agricultural authority. They can now broadcast it to nearby farmers.";
      els.dashEscalateMessage.className = "dash-alert-trigger-message is-success";
      els.dashEscalateMessage.hidden = false;
    } catch (err) {
      if (err.authRequired) {
        closeReportDetail();
        setState("auth");
        return;
      }
      els.dashEscalateBtn.disabled = false;
      els.dashEscalateBtn.textContent = "Alert Authority";
      els.dashEscalateMessage.textContent = err.message || "Could not reach the authority.";
      els.dashEscalateMessage.className = "dash-alert-trigger-message";
      els.dashEscalateMessage.hidden = false;
    }
  }

  async function triggerAlert() {
    if (!currentDetailReportId || !els.dashAlertTriggerBtn) return;
    els.dashAlertTriggerBtn.disabled = true;
    els.dashAlertTriggerBtn.textContent = "Sending…";
    els.dashAlertTriggerMessage.hidden = true;

    try {
      const result = await postJson(
        `${ALERTS_URL}/trigger/${encodeURIComponent(currentDetailReportId)}`,
        {}
      );
      const sent = result && typeof result.alertsSent === "number" ? result.alertsSent : 0;
      els.dashAlertTriggerMessage.textContent =
        sent === 0
          ? "Alert sent. No nearby farmers were eligible at this time (all may be on cooldown or none share your area)."
          : `Alert sent to ${sent} nearby farmer${sent === 1 ? "" : "s"}.`;
      els.dashAlertTriggerMessage.className = "dash-alert-trigger-message is-success";
      els.dashAlertTriggerMessage.hidden = false;
      els.dashAlertTriggerBtn.textContent = "Sent";
      els.dashAlertViewLink.hidden = false;
    } catch (err) {
      if (err.authRequired) {
        closeReportDetail();
        setState("auth");
        return;
      }
      els.dashAlertTriggerBtn.disabled = false;
      els.dashAlertTriggerBtn.textContent = "Send alert";
      els.dashAlertTriggerMessage.textContent = err.message;
      els.dashAlertTriggerMessage.className = "dash-alert-trigger-message";
      els.dashAlertTriggerMessage.hidden = false;
    }
  }

  // ---------------------------------------------------------------
  // Forward Case
  // ---------------------------------------------------------------

  function resetForwardUi() {
    if (els.dashForwardSection) {
      els.dashForwardSection.hidden = true;
      els.dashForwardStatus.textContent = "";
      els.dashForwardStatus.className = "dash-forward-status";
      els.dashForwardBtn.hidden = true;
      closeForwardPicker();
    }
  }

  function closeForwardPicker() {
    if (els.dashForwardPicker) {
      els.dashForwardPicker.hidden = true;
      els.dashForwardContactSelect.innerHTML = "";
      els.dashForwardMessage.hidden = true;
      els.dashForwardMessage.textContent = "";
      els.dashForwardMessage.className = "dash-forward-message";
      els.dashForwardConfirmBtn.disabled = false;
      els.dashForwardConfirmBtn.textContent = "Confirm forward";
    }
  }

  function contactOptionLabel(contact) {
    const detail = contact.organization || CATEGORY_LABELS[contact.category] || null;
    return detail ? `${contact.name} — ${detail}` : contact.name;
  }

  function renderForwardStatus(status) {
    if (!els.dashForwardSection) return;
    els.dashForwardSection.hidden = false;
    if (status && status.status === "FORWARDED") {
      const contactName = status.supportContact ? status.supportContact.name : "a support contact";
      els.dashForwardStatus.textContent = `Forwarded to ${contactName} on ${formatDate(status.forwardedAt)}`;
      els.dashForwardStatus.className = "dash-forward-status is-forwarded";
      els.dashForwardBtn.hidden = true;
      closeForwardPicker();
    } else {
      els.dashForwardStatus.textContent = "Not forwarded";
      els.dashForwardStatus.className = "dash-forward-status";
      els.dashForwardBtn.hidden = false;
    }
  }

  async function loadForwardStatus(id) {
    try {
      const status = await fetchJson(`${REPORTS_URL}/${encodeURIComponent(id)}/forward`);
      renderForwardStatus(status);
    } catch (err) {
      if (err.authRequired) {
        closeReportDetail();
        setState("auth");
        return;
      }
      if (els.dashForwardSection) {
        els.dashForwardSection.hidden = false;
        els.dashForwardStatus.textContent = "Couldn't load forwarding status.";
        els.dashForwardStatus.className = "dash-forward-status is-error";
        els.dashForwardBtn.hidden = true;
      }
    }
  }

  async function openForwardPicker() {
    if (!els.dashForwardPicker) return;
    els.dashForwardPicker.hidden = false;
    els.dashForwardContactSelect.innerHTML = `<option value="">Loading contacts…</option>`;
    els.dashForwardConfirmBtn.disabled = true;

    try {
      const contacts = await fetchJson(DIRECTORY_URL);
      const list = Array.isArray(contacts) ? contacts : [];
      if (list.length === 0) {
        els.dashForwardContactSelect.innerHTML = `<option value="">No support contacts available</option>`;
        els.dashForwardConfirmBtn.disabled = true;
        return;
      }
      els.dashForwardContactSelect.innerHTML = list
        .map((contact) => `<option value="${escapeHtml(contact.id)}">${escapeHtml(contactOptionLabel(contact))}</option>`)
        .join("");
      els.dashForwardConfirmBtn.disabled = false;
    } catch (err) {
      if (err.authRequired) {
        closeReportDetail();
        setState("auth");
        return;
      }
      els.dashForwardContactSelect.innerHTML = `<option value="">Couldn't load contacts</option>`;
      els.dashForwardConfirmBtn.disabled = true;
      els.dashForwardMessage.hidden = false;
      els.dashForwardMessage.textContent = err.message;
      els.dashForwardMessage.className = "dash-forward-message";
    }
  }

  async function confirmForward() {
    const selected = els.dashForwardContactSelect.value;
    if (!selected || !currentDetailReportId) return;

    els.dashForwardConfirmBtn.disabled = true;
    els.dashForwardConfirmBtn.textContent = "Forwarding…";
    els.dashForwardMessage.hidden = true;

    try {
      const result = await postJson(`${REPORTS_URL}/${encodeURIComponent(currentDetailReportId)}/forward`, {
        supportContactId: Number(selected),
      });
      els.dashForwardMessage.hidden = false;
      els.dashForwardMessage.textContent = "Case forwarded successfully.";
      els.dashForwardMessage.className = "dash-forward-message is-success";
      renderForwardStatus(result);
    } catch (err) {
      if (err.authRequired) {
        closeReportDetail();
        setState("auth");
        return;
      }
      els.dashForwardConfirmBtn.disabled = false;
      els.dashForwardConfirmBtn.textContent = "Confirm forward";
      els.dashForwardMessage.hidden = false;
      els.dashForwardMessage.textContent = err.message;
      els.dashForwardMessage.className = "dash-forward-message";
      if (err.code === "ALREADY_FORWARDED") {
        loadForwardStatus(currentDetailReportId);
      }
    }
  }

  // ---------------------------------------------------------------
  // Orchestration & Greetings
  // ---------------------------------------------------------------

  async function updateGreeting() {
    if (!els.dashUserGreeting) return;
    if (window.FolixAuth && typeof window.FolixAuth.getSession === "function") {
      try {
        const session = await window.FolixAuth.getSession();
        if (session && session.authenticated && session.farmer && session.farmer.name) {
          const namePart = session.farmer.name.trim().split(/\s+/)[0];
          els.dashUserGreeting.textContent = namePart.toUpperCase();
          return;
        }
      } catch (_) {}
    }
    els.dashUserGreeting.textContent = "USER";
  }

  async function loadDashboard(page) {
    setState("loading");
    try {
      updateGreeting();
      const [overview, totalElements] = await Promise.all([loadOverview(), loadHistory(page)]);
      setState(totalElements === 0 ? "empty" : "populated");
    } catch (err) {
      if (err.authRequired) {
        setState("auth");
        return;
      }
      if (els.dashErrorMessage) {
        els.dashErrorMessage.textContent = err.message || "Something went wrong while reaching the FOLIX server.";
      }
      setState("error");
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    cacheEls();

    if (els.dashRetryBtn) els.dashRetryBtn.addEventListener("click", () => loadDashboard(currentPage));
    if (els.dashPrevBtn) els.dashPrevBtn.addEventListener("click", () => loadDashboard(Math.max(0, currentPage - 1)));
    if (els.dashNextBtn) els.dashNextBtn.addEventListener("click", () => loadDashboard(currentPage + 1));
    if (els.dashPanelCloseBtn) els.dashPanelCloseBtn.addEventListener("click", closeReportDetail);
    if (els.dashForwardBtn) els.dashForwardBtn.addEventListener("click", openForwardPicker);
    if (els.dashForwardCancelBtn) els.dashForwardCancelBtn.addEventListener("click", closeForwardPicker);
    if (els.dashForwardConfirmBtn) els.dashForwardConfirmBtn.addEventListener("click", confirmForward);
    if (els.dashAlertTriggerBtn) els.dashAlertTriggerBtn.addEventListener("click", triggerAlert);
    if (els.dashEscalateBtn) els.dashEscalateBtn.addEventListener("click", escalateToAuthority);
    if (els.dashOverlay) {
      els.dashOverlay.addEventListener("click", (e) => {
        if (e.target === els.dashOverlay) closeReportDetail();
      });
    }
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && els.dashOverlay && !els.dashOverlay.hidden) closeReportDetail();
    });

    loadDashboard(0);
  });
})();
