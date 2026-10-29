(function () {
    'use strict';
    if (window.__alexYtGuardActive) return;
    window.__alexYtGuardActive = true;

    var host = (location && location.hostname) ? location.hostname : '';
    if (host !== 'www.youtube.com' && host !== 'm.youtube.com' && host !== 'music.youtube.com' && host !== 'youtube.com') {
        return;
    }

    var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];

    function stripFeedItems(items) {
        if (!Array.isArray(items)) return;
        for (var i = items.length - 1; i >= 0; i--) {
            var item = items[i];
            if (!item || typeof item !== 'object') continue;
            if (item.adSlotRenderer || item.promotedSparklesWebRenderer || item.searchPyvRenderer ||
                item.inFeedAdLayoutRenderer || item.promotedVideoRenderer ||
                (item.richItemRenderer && item.richItemRenderer.content &&
                    (item.richItemRenderer.content.adSlotRenderer ||
                     item.richItemRenderer.content.promotedSparklesWebRenderer ||
                     item.richItemRenderer.content.inFeedAdLayoutRenderer))) {
                items.splice(i, 1);
            }
        }
    }

    function stripAdKeys(node, depth) {
        if (!node || typeof node !== 'object' || depth > 5) return node;
        for (var i = 0; i < AD_KEYS.length; i++) {
            var k = AD_KEYS[i];
            if (Object.prototype.hasOwnProperty.call(node, k)) {
                try { delete node[k]; } catch (_) {}
            }
        }
        if (node.playerResponse && typeof node.playerResponse === 'object') {
            stripAdKeys(node.playerResponse, depth + 1);
        }
        if (Array.isArray(node.onResponseReceivedEndpoints)) {
            for (var j = 0; j < node.onResponseReceivedEndpoints.length; j++) {
                var ep = node.onResponseReceivedEndpoints[j];
                if (ep && ep.appendContinuationItemsAction && Array.isArray(ep.appendContinuationItemsAction.continuationItems)) {
                    stripFeedItems(ep.appendContinuationItemsAction.continuationItems);
                }
                if (ep && ep.reloadContinuationItemsCommand && Array.isArray(ep.reloadContinuationItemsCommand.continuationItems)) {
                    stripFeedItems(ep.reloadContinuationItemsCommand.continuationItems);
                }
            }
        }
        try {
            var tabs = node.contents && node.contents.twoColumnBrowseResultsRenderer &&
                node.contents.twoColumnBrowseResultsRenderer.tabs;
            if (Array.isArray(tabs)) {
                for (var t = 0; t < tabs.length; t++) {
                    var c = tabs[t] && tabs[t].tabRenderer && tabs[t].tabRenderer.content;
                    if (c && c.richGridRenderer && Array.isArray(c.richGridRenderer.contents)) {
                        stripFeedItems(c.richGridRenderer.contents);
                    }
                    if (c && c.sectionListRenderer && Array.isArray(c.sectionListRenderer.contents)) {
                        stripFeedItems(c.sectionListRenderer.contents);
                    }
                }
            }
            var watchResults = node.contents && node.contents.twoColumnWatchNextResults &&
                node.contents.twoColumnWatchNextResults.secondaryResults &&
                node.contents.twoColumnWatchNextResults.secondaryResults.secondaryResults &&
                node.contents.twoColumnWatchNextResults.secondaryResults.secondaryResults.results;
            if (Array.isArray(watchResults)) {
                stripFeedItems(watchResults);
            }
        } catch (_) {}
        return node;
    }

    var origParse = JSON.parse;
    JSON.parse = function (text, reviver) {
        var out = origParse.call(this, text, reviver);
        if (out && typeof out === 'object' && typeof text === 'string' && text.length > 64) {
            if (text.indexOf('adPlacements') !== -1 || text.indexOf('playerAds') !== -1 ||
                text.indexOf('adSlots') !== -1 || text.indexOf('adBreakHeartbeatParams') !== -1 ||
                text.indexOf('adSlotRenderer') !== -1 || text.indexOf('promotedSparklesWebRenderer') !== -1) {
                try { stripAdKeys(out, 0); } catch (_) {}
            }
        }
        return out;
    };

    if (typeof Response !== 'undefined' && Response.prototype && Response.prototype.json) {
        var origJson = Response.prototype.json;
        Response.prototype.json = function () {
            return origJson.call(this).then(function (data) {
                try { stripAdKeys(data, 0); } catch (_) {}
                return data;
            });
        };
    }

    function guardGlobalProp(propName) {
        var box = window[propName];
        if (box && typeof box === 'object') stripAdKeys(box, 0);
        try {
            Object.defineProperty(window, propName, {
                configurable: true,
                enumerable: true,
                get: function () { return box; },
                set: function (val) {
                    box = (val && typeof val === 'object') ? stripAdKeys(val, 0) : val;
                }
            });
        } catch (_) {}
    }

    guardGlobalProp('ytInitialPlayerResponse');
    guardGlobalProp('ytInitialData');
})();

