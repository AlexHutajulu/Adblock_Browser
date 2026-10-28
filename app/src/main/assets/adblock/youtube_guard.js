(function () {
    'use strict';
    if (window.__alexYtGuardActive) return;
    window.__alexYtGuardActive = true;

    var host = (location && location.hostname) ? location.hostname : '';
    if (host !== 'www.youtube.com' && host !== 'm.youtube.com' && host !== 'music.youtube.com' && host !== 'youtube.com') {
        return;
    }

    var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];

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
        return node;
    }

    var origParse = JSON.parse;
    JSON.parse = function (text, reviver) {
        var out = origParse.call(this, text, reviver);
        if (out && typeof out === 'object' && typeof text === 'string' && text.length > 64) {
            if (text.indexOf('adPlacements') !== -1 || text.indexOf('playerAds') !== -1 || text.indexOf('adSlots') !== -1) {
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
