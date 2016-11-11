/**
 * Copyright (C) 2013 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
(function() {

    var $ = ORBEON.jQuery;
    var AjaxServer = ORBEON.xforms.server.AjaxServer;

    var RepeatClassesSelector = ':not(.xforms-repeat-delimiter):not(.xforms-repeat-begin-end)';

    ORBEON.xforms.XBL.declareCompanion('fr|tabbable', {

        currentDragStartPrev     : null,   // this will be a repeat delimiter
        currentDragStartPosition : -1,
        drake                    : null,

        init: function() {

            var companion = this;

            if ($(companion.container).is('.fr-tabbable-dnd')) {

                var firstRepeatContainer = $(companion.container).find('.nav-tabs')[0];

                companion.drake =
                    dragula(
                        [ firstRepeatContainer ],
                        {
                            isContainer: function (el) {
                                return false; // only elements in drake.containers will be taken into account
                            },
                            moves: function (el, source, handle, sibling) {
                                var jEl = $(el);
                                return (
                                    jEl.prev().is('.xforms-repeat-delimiter') ||
                                    jEl.prev().prev().is('.xforms-repeat-delimiter')
                                ) &&
                                    jEl.is('.xforms-dnd-moves');
                            },
                            accepts: function (el, target, source, sibling) {
                                return sibling != null && $(sibling).is(':not(.gu-transit):not(.gu-mirror)');
                            },
                            invalid: function (el, handle) {
                                return false; // don't prevent any drags from initiating by default
                            },
                            direction                : 'vertical', // Y axis is considered when determining where an element would be dropped
                            copy                     : false,      // elements are moved by default, not copied
                            copySortSource           : false,      // elements in copy-source containers can be reordered
                            revertOnSpill            : false,      // spilling will put the element back where it was dragged from, if this is true
                            removeOnSpill            : false,      // spilling will `.remove` the element, if this is true
                            mirrorContainer          : firstRepeatContainer,
                            ignoreInputTextSelection : true        // allows users to select input text
                        }
                    );

                companion.drake.on('drag', function(el, source) {
                    var elPos = $(el).prevAll('li' + RepeatClassesSelector).length;

                    companion.currentDragStartPrev     = $(el).prev()[0];
                    companion.currentDragStartPosition = $(el).prevAll('.xforms-dnd-moves' + RepeatClassesSelector).length;
                });

                companion.drake.on('dragend', function(el) {
                    companion.currentDragStartPrev     = null;
                    companion.currentDragStartPosition = -1;
                });

                companion.drake.on('drop', function(el, target, source, sibling) {

                    var dndEnd = $(el).prevAll('.xforms-dnd-moves' + RepeatClassesSelector).length;
                    var siblingPos = $(sibling).prevAll('.xforms-dnd-moves' + RepeatClassesSelector).length;

                    // console.log(el);
                    // console.log(sibling);

                    var repeatId = $(el).prevAll('.xforms-repeat-begin-end').attr('id').substring('repeat-begin-'.length);

                    var beforeEl = companion.currentDragStartPrev;
                    var dndStart = companion.currentDragStartPosition;

                    if (dndStart != dndEnd) {
                        console.log("moved from " + dndStart + " to " + dndEnd);

                        // Restore order once we get an Ajax response back
                        AjaxServer.ajaxResponseReceived.add(function moveBack() {
                            $(beforeEl).after(el);
                            AjaxServer.ajaxResponseReceived.remove(moveBack);
                        });

                        // Thinking this should instead block input, but only after a while show a modal screen.
                        // ORBEON.util.Utils.displayModalProgressPanel(ORBEON.xforms.Controls.getForm(companion.container).id);

                        ORBEON.xforms.Document.dispatchEvent(
                            {
                                targetId  : repeatId,
                                eventName : 'xxforms-dnd',
                                properties: {
                                    'dnd-start' : dndStart + 1,
                                    'dnd-end'   : dndEnd + 1
                                }
                            }
                        );
                    }
                });
            }

            // 2016-10-13: We use our own logic to show/hide tabs based on position as we want to be able to
            // support dynamically repeated tabs.
            $(companion.container).on('click.tabbable.data-api', '[data-toggle = "tabbable"]', function (e) {

                e.preventDefault();  // don't allow anchor navigation
                e.stopPropagation(); // prevent ancestor tab handlers from running

                var newLi = $(this).parent(RepeatClassesSelector);

                if (newLi.is('.active'))
                    return;

                var tabPosition = newLi.prevAll(RepeatClassesSelector).length;
                companion.selectTab(tabPosition);
            });
        },

        destroy: function() {
            if (this.drake)
                this.drake.destroy();
        },

        selectTab: function(tabPosition) {

            if (tabPosition < 0)
                return;

            var allLis = $(this.container).find('> div > .nav-tabs').children(RepeatClassesSelector);
            if (tabPosition > allLis.length - 1)
                return;

            var newLi = $(allLis[tabPosition]);
            if (newLi.is('.active'))
                return;

            var allTabPanes = newLi.closest('.nav-tabs').nextAll('.tab-content').children('.tab-pane').filter(RepeatClassesSelector);
            var newTabPane  = allTabPanes[tabPosition];

            allLis.removeClass('active');
            allTabPanes.removeClass('active');

            newLi.addClass('active');
            $(newTabPane).addClass('active');
        }

        // _renumberIDsWorker: function (element, repeatDepth, newIndex) {
        //     // Rename ID on this element
        //     var repeatSeparatorPosition = element.id.indexOf(XF_REPEAT_SEPARATOR);
        //     if (repeatSeparatorPosition != -1) {
        //         var repeatIndexes = element.id.substring(repeatSeparatorPosition + 1).split(XF_REPEAT_INDEX_SEPARATOR);
        //         repeatIndexes[repeatDepth] = newIndex;
        //         var newID = element.id.substring(0, repeatSeparatorPosition) + XF_REPEAT_SEPARATOR + repeatIndexes.join(XF_REPEAT_INDEX_SEPARATOR);
        //         element.id = newID;
        //
        //     }
        //     // Do the same with all the children
        //     YAHOO.util.Dom.batch(YAHOO.util.Dom.getChildren(element), function (childElement) {
        //         this._renumberIDsWorker(childElement, repeatDepth, newIndex);
        //     }, this, true);
        // },
        //
        // /**
        //  * Renumber the IDs for a given repeat ID, for all the elements between the begin and end marker for that repeat
        //  * @param repeatID      E.g. repeat-begin-todo⊙1 for the repeat on to-dos in the first to-do list.
        //  */
        // _renumberIDs: function (repeatID) {
        //
        //     // Figure at what depth this repeat is
        //     var repeatDepth = 0;
        //     var currentRepeat = repeatID;
        //     var repeatSeparatorPosition = currentRepeat.indexOf(XF_REPEAT_SEPARATOR);
        //     if (repeatSeparatorPosition != -1)
        //         currentRepeat = currentRepeat.substring(0, repeatSeparatorPosition);
        //     while (true) {
        //         var parentRepeat = ORBEON.xforms.Globals.repeatTreeChildToParent[currentRepeat];
        //         if (! parentRepeat) break;
        //         repeatDepth++;
        //         currentRepeat = parentRepeat;
        //     }
        //
        //     // Go through the top elements and change the IDs of all the children
        //     var currentElement = ORBEON.util.Dom.get("repeat-begin-" + repeatID);
        //     var newIndex = 0;
        //     while (true) {
        //         currentElement = YAHOO.util.Dom.getNextSibling(currentElement);
        //         if (currentElement == null || $(currentElement).is('.xforms-repeat-begin-end'))
        //             break;
        //         if (! $(currentElement).is('.xforms-repeat-delimiter, .xforms-repeat-template')) {
        //             newIndex++;
        //             this._renumberIDsWorker(currentElement, repeatDepth, newIndex);
        //         }
        //     }
        // }
    });

})();
