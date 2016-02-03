/* global module:true */
module.exports = function (grunt) {
  'use strict';

  require('load-grunt-tasks')(grunt);

  grunt.initConfig({

    dirs: {
      js: 'js',
      css: 'css',
      img: 'img',
      sass: 'sass',
      vendor: 'js/vendor'
    },

    pkg: grunt.file.readJSON( 'package.json' ),

    clean: {
      dist: 'dist'
    },

    // concat: {
    //   dist: {
    //     src: [
    //       'src/<%= dirs.js %>/buddycloud-styles.js',
    //       'src/<%= dirs.js %>/vendor/highlight.min.js'
    //     ],
    //     dest: 'src/<%= dirs.js %>/buddycloud-styles.js'
    //   }
    // },

    uglify: {
      options: {
        mangle: true,
        compress: true
      },
      target: {
        files: {
          'dist/<%= dirs.js %>/buddycloud-styles.min.js': [
            'src/<%= dirs.js %>/vendor/highlight.min.js',
            'src/<%= dirs.js %>/buddycloud-styles.js'
          ]
        }
      }
    },

    cssmin: {
      dist: {
        expand: true,
        cwd: 'dist/css/',
        src: ['*.css', '!*.min.css'],
        dest: 'dist/css/',
        ext: '.min.css'
      },
      dev: {
        expand: true,
        cwd: 'docs/theme/static/vendor/buddycloud-styles/dist/<%= dirs.css %>/',
        src: ['*.css', '!*.min.css'],
        dest: 'docs/theme/static/vendor/buddycloud-styles/dist/<%= dirs.css %>/',
        ext: '.min.css'
      }
    },

    compass: {
      dist: {
        options: {
          sassDir: 'src/<%= dirs.sass %>',
          cssDir: 'dist/<%= dirs.css %>',
          imagesDir: 'src/<%= dirs.img %>',
          relativeAssets: true,
          outputStyle: 'expanded'
        }
      },
      dev: {
        options: {
          sassDir: 'src/<%= dirs.sass %>',
          cssDir: 'docs/theme/static/vendor/buddycloud-styles/dist/<%= dirs.css %>',
          imagesDir: 'src/<%= dirs.img %>',
          relativeAssets: true,
          outputStyle: 'expanded'
        }
      }
    },

    watch: {
      options: {
        livereload: true
      },
      sass: {
        files: 'src/sass/**/*',
        tasks: [
          'compass:dev',
          'cssmin:dev'
        ]
      },
      js: {
        files: [
          'src/<%= dirs.js %>/**/*.js'
        ],
        tasks: [
          'copy:dist',
          'uglify',
          'copy:dev'
        ]
      }
    },

    copy: {
      dist: {
        files: [
          {
            expand: true,
            cwd: 'src/',
            src: ['**/*.js', 'img/**/*'],
            dest: 'dist/'
          }
        ]
      },
      dev: {
        files: [
          {
            expand: true,
            cwd: 'dist/',
            src: ['**/*.js'],
            dest: 'docs/theme/static/vendor/buddycloud-styles/dist/'
          }
        ]
      }
    }

  });

  grunt.registerTask('default', ['watch']);

  grunt.registerTask('build', [
    'clean:dist',
    'copy:dist',
    'compass:dist',
    'cssmin:dist',
    // 'concat',
    'uglify'
  ]);

};
