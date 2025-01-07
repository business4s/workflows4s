import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
    title: 'Workflows4s',
    tagline: 'Simple, Composable, Business-oriented Workflows for Scala',
    favicon: 'img/favicon.ico',

    // GitHub pages deployment config.
    url: 'https://business4s.github.io/',
    baseUrl: '/workflows4s/',
    organizationName: 'business4s',
    projectName: 'workflows4s',
    trailingSlash: true,

    onBrokenLinks: 'throw',
    onBrokenMarkdownLinks: 'warn',

    // Even if you don't use internationalization, you can use this field to set
    // useful metadata like html lang. For example, if your site is Chinese, you
    // may want to replace "en" with "zh-Hans".
    i18n: {
        defaultLocale: 'en',
        locales: ['en'],
    },

    markdown: {
        mermaid: true,
    },
    themes: ['@docusaurus/theme-mermaid'],

    presets: [
        [
            'classic',
            {
                docs: {
                    sidebarPath: './sidebars.ts',
                    editUrl: 'https://github.com/business4s/workflows4s/webstie',
                    beforeDefaultRemarkPlugins: [
                        [
                            require('remark-code-snippets'),
                            {baseDir: "../workflows4s-example/src/"}
                        ]
                    ],
                },
                theme: {
                    customCss: './src/css/custom.css',
                },
            } satisfies Preset.Options,
        ],
    ],

    themeConfig: {
        // Replace with your project's social card
        image: 'img/docusaurus-social-card.jpg',
        navbar: {
            title: 'Workflows4s',
            logo: {
                alt: 'Workflows4s Logo',
                src: 'img/workflows4s-logo.png',
            },
            items: [
                {
                    type: 'docSidebar',
                    sidebarId: 'tutorialSidebar',
                    position: 'left',
                    label: 'Docs',
                },
                {
                    href: 'https://github.com/business4s/workflows4s',
                    label: 'GitHub',
                    position: 'right',
                },
            ],
        },
        footer: {
            style: 'dark',
            links: [
                // {
                //   title: 'Docs',
                //   items: [
                //     {
                //       label: 'Docs',
                //       to: '/docs/intro',
                //     },
                //   ],
                // },
                // {
                //   title: 'Community',
                //   items: [
                //     {
                //       label: 'Stack Overflow',
                //       href: 'https://stackoverflow.com/questions/tagged/docusaurus',
                //     },
                //     {
                //       label: 'Discord',
                //       href: 'https://discordapp.com/invite/docusaurus',
                //     },
                //     {
                //       label: 'Twitter',
                //       href: 'https://twitter.com/docusaurus',
                //     },
                //   ],
                // },
                // {
                //   title: 'More',
                //   items: [
                //     {
                //       label: 'Blog',
                //       to: '/blog',
                //     },
                //     {
                //       label: 'GitHub',
                //       href: 'https://github.com/facebook/docusaurus',
                //     },
                //   ],
                // },
            ],
            // copyright: `Copyright © ${new Date().getFullYear()} My Project, Inc. Built with Docusaurus.`,
        },
        prism: {
            theme: prismThemes.github,
            darkTheme: prismThemes.dracula,
            additionalLanguages: ['java', 'scala', "json"]
        },
        customFields: {
            workflows4sVersion: "unreleased",
        },
    } satisfies Preset.ThemeConfig,
};

export default config;
