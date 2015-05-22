/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2014 - 2015 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
;/**
* navMenu - Hide and show menu in small screen
*/
(function($) {
  $.fn.navMenu = function(options) {
    // override the default options by parameter
    var opts = $.extend({}, $.fn.navMenu.defaults, options);
    var $this = this; // script
    $this.each(function() {
      var $self = $(this); // dom element
      var $btn = $self.find(opts.menuOpener);
      var $menu = $self.find(opts.menuContainer);
      var open = $menu.hasClass(opts.menuActiveClass);
      var toggleMenu = function(e) {
        $.log("click menu");
        e.preventDefault();
        open = $menu.hasClass(opts.menuActiveClass);
        if (open) {
          $menu.slideUp(function(){
            $menu.removeClass(opts.menuActiveClass);
            // prevent inline display setting
            $menu.css({"display":""});
          });

        } else {
          $menu.slideDown(function(){
            $menu.addClass(opts.menuActiveClass);
            // prevent inline display setting
            $menu.css({"display":""});
          });

        }
        $.log("menu open:", open);
      }
      $.log("menu open:", open);
      // bind click
      $btn.on('click', toggleMenu);
    });

  };
  /**
   * Default settings
   */
  $.fn.navMenu.defaults = {
    menuOpener: ".menu-opener",
    menuContainer: ".navlist-main",
    menuActiveClass: "active"
  };
})(jQuery);
